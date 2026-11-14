package vn.edu.ptit.exam.server.lb;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.common.config.Config;
import vn.edu.ptit.exam.common.dto.ExamTicket;
import vn.edu.ptit.exam.common.dto.Message;
import vn.edu.ptit.exam.common.enums.ErrorCode;
import vn.edu.ptit.exam.common.enums.MessageType;
import vn.edu.ptit.exam.common.util.HmacUtil;
import vn.edu.ptit.exam.common.util.JsonUtil;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Load Balancer running in Server 1 process on port 9000.
 * Core responsibilities:
 * - Listens on port 9000, limits to 500 connections (429 BUSY + retryAfter).
 * - 4 Server state table (UNKNOWN/UP/DOWN), 1s heartbeat PING thread (3 missed -> DOWN, 3 PONGs -> UP, 30s warmup).
 * - Load balancing algorithms: Round Robin and Weighted Least Connections (hot-swappable via ADMIN_SET_ALGO).
 * - Session registry (enforcing 409 SESSION_IN_EXAM, SESSION_REPLACED, RESUME_SESSION).
 * - Sticky exam registry (Invariant 8).
 * - FIFO queue for START_EXAM (max 50 concurrent, QUEUED notices every 3s).
 * - Admin monitoring: ADMIN_STATUS, STATUS_REQUEST, LOG_QUERY.
 */
public class LoadBalancer {

    private static final Logger log = LoggerFactory.getLogger(LoadBalancer.class);

    public static final int DEFAULT_PORT = 9000;
    public static final int MAX_CONNECTIONS = 500;

    private final int port;
    private int minServersUp = 1; // Default 1 for standalone/test; 3 in full cluster

    private ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread acceptThread;

    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final List<BackendServerInfo> backendServers = new ArrayList<>();
    private final BackendConnectionPool connectionPool = new BackendConnectionPool();

    private final ServerSelector serverSelector = new ServerSelector();
    private final SessionRegistry sessionRegistry = new SessionRegistry();
    private final ExamRegistry examRegistry = new ExamRegistry();
    private final StartExamQueueManager startExamQueueManager = new StartExamQueueManager();
    private final DistributionLogger distributionLogger = new DistributionLogger();
    private final AtomicInteger failoverCount = new AtomicInteger(0);

    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "lb-heartbeat");
        t.setDaemon(true);
        return t;
    });

    private final ExecutorService clientHandlerPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "lb-client-handler");
        t.setDaemon(true);
        return t;
    });

    public LoadBalancer() {
        this(Config.getInstance().getLbPort());
    }

    public LoadBalancer(int port) {
        this.port = port;
        initDefaultServers();
    }

    private void initDefaultServers() {
        Config config = Config.getInstance();
        // Server 1 (port 9001, weight 1)
        backendServers.add(new BackendServerInfo(1, config.getServerHost(1), config.getServerPort(1), config.getServerWeight(1)));
        // Servers 2, 3, 4 (ports 9002-9004, weight 2)
        backendServers.add(new BackendServerInfo(2, config.getServerHost(2), config.getServerPort(2), config.getServerWeight(2)));
        backendServers.add(new BackendServerInfo(3, config.getServerHost(3), config.getServerPort(3), config.getServerWeight(3)));
        backendServers.add(new BackendServerInfo(4, config.getServerHost(4), config.getServerPort(4), config.getServerWeight(4)));

        for (BackendServerInfo s : backendServers) {
            connectionPool.registerServer(s.getServerId(), s.getHost(), s.getPort());
        }
    }

    public synchronized void start() throws IOException {
        if (running.compareAndSet(false, true)) {
            serverSocket = new ServerSocket(port);
            serverSocket.setReuseAddress(true);

            acceptThread = new Thread(this::runAcceptLoop, "lb-accept-thread");
            acceptThread.setDaemon(true);
            acceptThread.start();

            // Start 1-second heartbeat task
            heartbeatScheduler.scheduleAtFixedRate(this::performHeartbeat, 1, 1, TimeUnit.SECONDS);

            log.info("Load Balancer is LISTENING on port {}", port);
        }
    }

    public synchronized void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping Load Balancer...");
            heartbeatScheduler.shutdown();
            startExamQueueManager.shutdown();
            connectionPool.close();
            clientHandlerPool.shutdown();

            try {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    serverSocket.close();
                }
            } catch (IOException ignored) {}

            log.info("Load Balancer stopped.");
        }
    }

    // ==========================================
    // 1. Client Acceptance & Connection Limiter
    // ==========================================

    private void runAcceptLoop() {
        while (running.get()) {
            try {
                Socket clientSocket = serverSocket.accept();
                clientSocket.setTcpNoDelay(true);

                int currentConns = activeConnections.incrementAndGet();
                if (currentConns > MAX_CONNECTIONS) {
                    log.warn("Connection limit exceeded ({}/{}). Rejecting with 429 BUSY.", currentConns, MAX_CONNECTIONS);
                    activeConnections.decrementAndGet();
                    rejectBusy(clientSocket);
                    continue;
                }

                clientHandlerPool.execute(() -> handleClientSocket(clientSocket));
            } catch (IOException e) {
                if (running.get()) {
                    log.error("LB accept error", e);
                }
            }
        }
    }

    private void rejectBusy(Socket socket) {
        try (socket; BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            Message busyMsg = Message.busy("SYSTEM", "BUSY-" + System.currentTimeMillis(), 3);
            writer.write(JsonUtil.toJson(busyMsg) + "\n");
            writer.flush();
        } catch (IOException ignored) {}
    }

    private void handleClientSocket(Socket socket) {
        BufferedWriter writer = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                Message req = null;
                try {
                    req = JsonUtil.fromJson(line, Message.class);
                } catch (Exception e) {
                    log.warn("Failed to parse client message: {}", line);
                    continue;
                }

                if (req == null) continue;
                processClientRequest(req, socket, writer);
            }
        } catch (IOException e) {
            log.debug("Client socket closed: {}", e.getMessage());
        } finally {
            activeConnections.decrementAndGet();
            sessionRegistry.unregisterSocket(socket);
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    // ==========================================
    // 2. Request Routing & Handler Logic
    // ==========================================

    private void processClientRequest(Message req, Socket socket, BufferedWriter writer) {
        long startTime = System.currentTimeMillis();
        String typeStr = req.getType();

        // 1. Direct handling of PING
        if (MessageType.PING.name().equalsIgnoreCase(typeStr)) {
            handleDirectPing(req, writer);
            return;
        }

        // 2. Admin Operations handled directly at LB
        if (MessageType.ADMIN_STATUS.name().equalsIgnoreCase(typeStr)) {
            handleAdminStatus(req, writer);
            return;
        }
        if (MessageType.ADMIN_SET_ALGO.name().equalsIgnoreCase(typeStr)) {
            handleAdminSetAlgo(req, writer);
            return;
        }
        if (MessageType.LOG_QUERY.name().equalsIgnoreCase(typeStr)) {
            handleLogQuery(req, writer);
            return;
        }

        // 3. Check for available UP servers
        List<BackendServerInfo> upServers = getUpServers();
        if (upServers.size() < minServersUp) {
            log.warn("No UP backend servers available (active={}, required={})", upServers.size(), minServersUp);
            sendResponse(writer, Message.error(req.getType(), req.getRequestId(), ErrorCode.NO_SERVER_AVAILABLE));
            distributionLogger.log(req.getRequestId(), req.getType(), null, 0, System.currentTimeMillis() - startTime, "ERROR", "NO_SERVER_AVAILABLE");
            return;
        }

        // 4. Handle LOGIN (dual login check)
        if (MessageType.LOGIN.name().equalsIgnoreCase(typeStr)) {
            handleLoginRequest(req, socket, writer, startTime);
            return;
        }

        // 5. Handle RESUME_SESSION
        if (MessageType.RESUME_SESSION.name().equalsIgnoreCase(typeStr)) {
            handleResumeSession(req, socket, writer, startTime);
            return;
        }

        // 6. Handle START_EXAM (Sticky check + FIFO queue)
        if (MessageType.START_EXAM.name().equalsIgnoreCase(typeStr)) {
            handleStartExamRequest(req, socket, writer, startTime);
            return;
        }

        // 7. General routing (Sticky for attempts, or session server)
        routeGeneralRequest(req, socket, writer, startTime);
    }

    private void handleDirectPing(Message req, BufferedWriter writer) {
        // Return PONG with load and server time, and list of DOWN servers
        Map<String, Object> payload = new HashMap<>();
        payload.put("load", activeConnections.get());
        payload.put("downServers", getDownServerIds());

        Message pong = Message.ok(MessageType.PONG, req.getRequestId(), payload);
        pong.setServerTime(System.currentTimeMillis());
        sendResponse(writer, pong);
    }

    private void handleLoginRequest(Message req, Socket socket, BufferedWriter writer, long startTime) {
        String username = null;
        if (req.getPayload() != null) {
            try {
                JsonNode node = JsonUtil.readTree(JsonUtil.toJson(req.getPayload()));
                if (node.has("username")) username = node.get("username").asText().trim();
            } catch (Exception ignored) {}
        }

        // Check dual login in SessionRegistry
        if (username != null) {
            SessionRegistry.ClientSessionEntry existing = sessionRegistry.getSessionByUserId(username);
            if (existing != null && existing.isAlive()) {
                if (existing.isInExam()) {
                    // Rule: 409 SESSION_IN_EXAM
                    Message err = Message.error(MessageType.LOGIN, req.getRequestId(), ErrorCode.SESSION_IN_EXAM, "Tài khoản đang trong ca thi ở nơi khác");
                    sendResponse(writer, err);
                    distributionLogger.log(req.getRequestId(), req.getType(), username, 0, System.currentTimeMillis() - startTime, "ERROR", "SESSION_IN_EXAM");
                    return;
                } else {
                    // Rule: replace session
                    Message replaceMsg = Message.error(MessageType.SESSION_REPLACED, "SYS-" + System.currentTimeMillis(),
                            ErrorCode.SESSION_IN_EXAM, "Tài khoản của bạn đã được đăng nhập từ một thiết bị khác.");
                    existing.sendPushMessage(replaceMsg);
                    existing.closeSocket();
                }
            }
        }

        // Select server via algorithm
        Optional<BackendServerInfo> selected = serverSelector.selectServer(backendServers);
        if (selected.isEmpty()) {
            sendResponse(writer, Message.error(MessageType.LOGIN, req.getRequestId(), ErrorCode.NO_SERVER_AVAILABLE));
            return;
        }

        BackendServerInfo target = selected.get();
        target.incrementConnections();
        final String finalUser = username;

        connectionPool.forward(target.getServerId(), req).whenComplete((resp, ex) -> {
            target.decrementConnections();
            long duration = System.currentTimeMillis() - startTime;

            if (ex != null || resp == null) {
                sendResponse(writer, Message.error(MessageType.LOGIN, req.getRequestId(), ErrorCode.INTERNAL, "Lỗi kết nối máy chủ nghiệp vụ"));
                distributionLogger.log(req.getRequestId(), req.getType(), finalUser, target.getServerId(), duration, "ERROR", ex != null ? ex.getMessage() : "NULL");
                return;
            }

            if (resp.isOk() && finalUser != null) {
                try {
                    sessionRegistry.registerLogin(finalUser, finalUser, socket, writer, target.getServerId());
                    SessionRegistry.ClientSessionEntry sess = sessionRegistry.getSessionByUserId(finalUser);
                    if (sess != null && resp.getToken() != null) {
                        sess.setToken(resp.getToken());
                    }
                } catch (SessionRegistry.SessionException e) {
                    sendResponse(writer, Message.error(MessageType.LOGIN, req.getRequestId(), e.getErrorCode(), e.getMessage()));
                    return;
                }
            }

            sendResponse(writer, resp);
            distributionLogger.log(req.getRequestId(), req.getType(), finalUser, target.getServerId(), duration, resp.getStatus(), resp.getMessage());
        });
    }

    private void handleResumeSession(Message req, Socket socket, BufferedWriter writer, long startTime) {
        String tokenStr = req.getToken();
        String userId = null;
        if (tokenStr != null) {
            try {
                var auth = HmacUtil.parseAndVerifyToken(tokenStr, Config.getInstance().getHmacSecretKey());
                if (auth != null) userId = auth.getUsername();
            } catch (Exception ignored) {}
        }

        if (userId != null) {
            sessionRegistry.resumeSession(userId, socket, writer);
        }

        // Route to sticky server if user has active attempt, else select server
        Optional<BackendServerInfo> target = selectTargetServer(req, userId);
        if (target.isEmpty()) {
            sendResponse(writer, Message.error(MessageType.RESUME_SESSION, req.getRequestId(), ErrorCode.NO_SERVER_AVAILABLE));
            return;
        }

        BackendServerInfo server = target.get();
        server.incrementConnections();
        final String finalUser = userId;

        connectionPool.forward(server.getServerId(), req).whenComplete((resp, ex) -> {
            server.decrementConnections();
            long duration = System.currentTimeMillis() - startTime;
            if (ex != null || resp == null) {
                sendResponse(writer, Message.error(MessageType.RESUME_SESSION, req.getRequestId(), ErrorCode.INTERNAL));
                distributionLogger.log(req.getRequestId(), req.getType(), finalUser, server.getServerId(), duration, "ERROR", ex != null ? ex.getMessage() : "NULL");
            } else {
                sendResponse(writer, resp);
                distributionLogger.log(req.getRequestId(), req.getType(), finalUser, server.getServerId(), duration, resp.getStatus(), resp.getMessage());
            }
        });
    }

    private void handleStartExamRequest(Message req, Socket socket, BufferedWriter writer, long startTime) {
        String examId = null;
        if (req.getPayload() != null) {
            try {
                JsonNode node = JsonUtil.readTree(JsonUtil.toJson(req.getPayload()));
                if (node.has("examId")) examId = node.get("examId").asText().trim();
            } catch (Exception ignored) {}
        }

        SessionRegistry.ClientSessionEntry session = sessionRegistry.getSessionBySocket(socket);
        String userId = session != null ? session.getUserId() : null;

        // Sticky check: if (userId, examId) already assigned to an UP server, forward directly
        if (userId != null && examId != null) {
            Optional<Integer> stickyServerId = examRegistry.getServerForUserExam(userId, examId);
            if (stickyServerId.isPresent()) {
                BackendServerInfo s = getBackendServer(stickyServerId.get());
                if (s != null && s.getStatus() == ServerStatus.UP) {
                    forwardStartExam(req, s, userId, examId, writer, startTime);
                    return;
                }
            }
        }

        // New attempt: pass through FIFO queue manager
        final String finalUserId = userId;
        final String finalExamId = examId;

        startExamQueueManager.submitOrQueue(req, writer, readyReq -> {
            Optional<BackendServerInfo> selected = serverSelector.selectServer(backendServers);
            if (selected.isEmpty()) {
                sendResponse(writer, Message.error(MessageType.START_EXAM, readyReq.getRequestId(), ErrorCode.NO_SERVER_AVAILABLE));
                startExamQueueManager.onStartExamCompleted();
                return;
            }
            forwardStartExam(readyReq, selected.get(), finalUserId, finalExamId, writer, startTime);
        });
    }

    private void forwardStartExam(Message req, BackendServerInfo target, String userId, String examId, BufferedWriter writer, long startTime) {
        target.incrementConnections();

        connectionPool.forward(target.getServerId(), req).whenComplete((resp, ex) -> {
            target.decrementConnections();
            startExamQueueManager.onStartExamCompleted();
            long duration = System.currentTimeMillis() - startTime;

            if (ex != null || resp == null) {
                sendResponse(writer, Message.error(MessageType.START_EXAM, req.getRequestId(), ErrorCode.INTERNAL, "Lỗi kết nối máy chủ"));
                distributionLogger.log(req.getRequestId(), req.getType(), userId, target.getServerId(), duration, "ERROR", ex != null ? ex.getMessage() : "NULL");
                return;
            }

            if (resp.isOk() && resp.getPayload() != null) {
                try {
                    JsonNode node = JsonUtil.readTree(JsonUtil.toJson(resp.getPayload()));
                    String attemptId = node.has("attemptId") ? node.get("attemptId").asText().trim() : null;
                    ExamTicket ticket = null;
                    if (node.has("ticket")) {
                        ticket = JsonUtil.fromJson(node.get("ticket").toString(), ExamTicket.class);
                    }
                    if (attemptId != null) {
                        examRegistry.registerExam(userId, examId, attemptId, target.getServerId(), ticket);
                        if (userId != null) {
                            sessionRegistry.setExamActive(userId, true);
                            SessionRegistry.ClientSessionEntry sess = sessionRegistry.getSessionByUserId(userId);
                            if (sess != null) {
                                sess.setAttemptId(attemptId);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            sendResponse(writer, resp);
            distributionLogger.log(req.getRequestId(), req.getType(), userId, target.getServerId(), duration, resp.getStatus(), resp.getMessage());
        });
    }

    private void routeGeneralRequest(Message req, Socket socket, BufferedWriter writer, long startTime) {
        SessionRegistry.ClientSessionEntry session = sessionRegistry.getSessionBySocket(socket);
        String userId = session != null ? session.getUserId() : null;

        Optional<BackendServerInfo> target = selectTargetServer(req, userId);
        if (target.isEmpty()) {
            sendResponse(writer, Message.error(req.getType(), req.getRequestId(), ErrorCode.NO_SERVER_AVAILABLE));
            return;
        }

        BackendServerInfo server = target.get();
        server.incrementConnections();

        connectionPool.forward(server.getServerId(), req).whenComplete((resp, ex) -> {
            server.decrementConnections();
            long duration = System.currentTimeMillis() - startTime;

            if (ex != null || resp == null) {
                log.warn("Forwarding {} to Server {} failed: {}", req.getType(), server.getServerId(), ex != null ? ex.getMessage() : "null");
                server.recordFailure();
                if (server.getStatus() == ServerStatus.DOWN) {
                    triggerFailoverForServer(server.getServerId());
                }

                // If attempt was reassigned due to failover, retry once on the replacement server
                if (req.getAttemptId() != null) {
                    Optional<Integer> newServerId = examRegistry.getServerForAttempt(req.getAttemptId());
                    if (newServerId.isPresent() && newServerId.get() != server.getServerId()) {
                        BackendServerInfo newServer = getBackendServer(newServerId.get());
                        if (newServer != null && newServer.getStatus() == ServerStatus.UP) {
                            connectionPool.forward(newServer.getServerId(), req).whenComplete((r2, ex2) -> {
                                if (ex2 != null || r2 == null) {
                                    sendResponse(writer, Message.error(req.getType(), req.getRequestId(), ErrorCode.INTERNAL));
                                } else {
                                    sendResponse(writer, r2);
                                }
                            });
                            return;
                        }
                    }
                }

                sendResponse(writer, Message.error(req.getType(), req.getRequestId(), ErrorCode.INTERNAL));
                distributionLogger.log(req.getRequestId(), req.getType(), userId, server.getServerId(), duration, "ERROR", ex != null ? ex.getMessage() : "NULL");
                return;
            }

            // Check if SUBMIT was successful -> mark exam complete
            if (MessageType.SUBMIT.name().equalsIgnoreCase(req.getType()) && resp.isOk() && userId != null) {
                sessionRegistry.setExamActive(userId, false);
            }

            sendResponse(writer, resp);
            distributionLogger.log(req.getRequestId(), req.getType(), userId, server.getServerId(), duration, resp.getStatus(), resp.getMessage());
        });
    }

    private Optional<BackendServerInfo> selectTargetServer(Message req, String userId) {
        // Sticky attempt check
        String attemptId = req.getAttemptId();
        if (attemptId != null) {
            Optional<Integer> stickyId = examRegistry.getServerForAttempt(attemptId);
            if (stickyId.isPresent()) {
                BackendServerInfo s = getBackendServer(stickyId.get());
                if (s != null && s.getStatus() == ServerStatus.UP) {
                    return Optional.of(s);
                }
            }
        }

        // User session stickiness
        if (userId != null) {
            SessionRegistry.ClientSessionEntry session = sessionRegistry.getSessionByUserId(userId);
            if (session != null) {
                BackendServerInfo s = getBackendServer(session.getAssignedServerId());
                if (s != null && s.getStatus() == ServerStatus.UP) {
                    return Optional.of(s);
                }
            }
        }

        // Fallback to load balancing algorithm
        return serverSelector.selectServer(backendServers);
    }

    // ==========================================
    // 3. Heartbeat & Health Check Worker
    // ==========================================

    private void performHeartbeat() {
        for (BackendServerInfo server : backendServers) {
            long tSend = System.currentTimeMillis();
            Message pingReq = Message.request(MessageType.PING, "LB-PING-" + server.getServerId() + "-" + UUID.randomUUID());
            pingReq.setPayload(Map.of("downServers", getDownServerIds()));

            connectionPool.forward(server.getServerId(), pingReq, 2).thenAccept(pong -> {
                long tRecv = System.currentTimeMillis();
                long rtt = tRecv - tSend;
                Long serverTime = pong.getServerTime();
                long sTime = serverTime != null ? serverTime : tRecv;

                ServerStatus prevStatus = server.getStatus();
                server.recordPong(sTime, rtt, tRecv);

                if (prevStatus != ServerStatus.UP && server.getStatus() == ServerStatus.UP) {
                    log.info("Server {} transitioned to UP (3 consecutive PONGs confirmed)", server.getServerId());
                }

                // Check clock skew > 1 second
                if (Math.abs(server.getClockSkewMs()) > 1000) {
                    log.warn("ALERT: Clock skew on Server {} exceeded 1000ms (skew={}ms)!", server.getServerId(), server.getClockSkewMs());
                }
            }).exceptionally(ex -> {
                ServerStatus prevStatus = server.getStatus();
                server.recordFailure();
                if (prevStatus == ServerStatus.UP && server.getStatus() == ServerStatus.DOWN) {
                    log.error("ALERT: Server {} transitioned to DOWN (3 consecutive missed heartbeats)!", server.getServerId());
                    triggerFailoverForServer(server.getServerId());
                }
                return null;
            });
        }
    }

    // ==========================================
    // 3.5. Failover Orchestration (Luồng 8)
    // ==========================================

    public synchronized void triggerFailoverForServer(int deadServerId) {
        log.warn("Triggering failover for Server {}...", deadServerId);
        failoverCount.incrementAndGet();

        // 1. Get attempts on dead server
        List<String> attempts = examRegistry.getAttemptsForServer(deadServerId);

        // 2. Also check sessions on dead server
        List<SessionRegistry.ClientSessionEntry> deadSessions = sessionRegistry.getSessionsForServer(deadServerId);

        for (String attemptId : attempts) {
            String userId = examRegistry.getUserForAttempt(attemptId);
            SessionRegistry.ClientSessionEntry session = (userId != null) ? sessionRegistry.getSessionByUserId(userId) : null;
            if (session == null) {
                session = sessionRegistry.getSessionByAttemptId(attemptId);
            }
            failoverSingleAttempt(attemptId, userId, session, deadServerId);
        }

        // For any remaining sessions on deadServerId that weren't in an attempt
        for (SessionRegistry.ClientSessionEntry session : deadSessions) {
            if (session.getAttemptId() == null || !attempts.contains(session.getAttemptId())) {
                failoverNonExamSession(session, deadServerId);
            }
        }
    }

    private void failoverSingleAttempt(String attemptId, String userId, SessionRegistry.ClientSessionEntry session, int deadServerId) {
        List<BackendServerInfo> healthyServers = new ArrayList<>();
        for (BackendServerInfo s : backendServers) {
            if (s.getServerId() != deadServerId && s.getStatus() == ServerStatus.UP) {
                healthyServers.add(s);
            }
        }

        if (healthyServers.isEmpty()) {
            log.error("No healthy servers available for failover of attempt {}", attemptId);
            if (session != null && session.isAlive()) {
                session.sendPushMessage(Message.error(MessageType.SERVER_SWITCHING, "SYS-SW-" + UUID.randomUUID(),
                        ErrorCode.NO_SERVER_AVAILABLE, "Tất cả các máy chủ hiện tại đều không khả dụng."));
            }
            return;
        }

        // Choose server with least total load (in-flight + assigned attempts), weighted (Luồng 8 Bước 2)
        healthyServers.sort(Comparator
                .comparingDouble((BackendServerInfo s) -> (double) (s.getActiveConnections() + examRegistry.getAttemptsForServer(s.getServerId()).size()) / Math.max(0.01, s.getEffectiveWeight()))
                .thenComparingInt(BackendServerInfo::getServerId));
        BackendServerInfo replacement = healthyServers.get(0);
        replacement.incrementConnections();

        // Update registries immediately (Luồng 8 Bước 2)
        examRegistry.reassignServer(attemptId, replacement.getServerId());
        if (session != null) {
            session.setAssignedServerId(replacement.getServerId());
        }

        log.info("Failing over attempt {} from Server {} to Server {}", attemptId, deadServerId, replacement.getServerId());

        // Notify client with SERVER_SWITCHING push notice
        if (session != null && session.isAlive()) {
            Message switchingNotice = Message.request(MessageType.SERVER_SWITCHING, "SYS-SW-" + UUID.randomUUID());
            switchingNotice.setMessage("Máy chủ " + deadServerId + " đang gặp sự cố. Đang chuyển sang máy chủ " + replacement.getServerId() + "...");
            Map<String, Object> swPayload = new HashMap<>();
            swPayload.put("previousServerId", deadServerId);
            swPayload.put("newServerId", replacement.getServerId());
            swPayload.put("attemptId", attemptId);
            switchingNotice.setPayload(swPayload);
            session.sendPushMessage(switchingNotice);
        }

        // Send internal RESUME_REQUEST to replacement server
        Message resumeReq = Message.request(MessageType.RESUME_REQUEST, "LB-RESUME-" + attemptId + "-" + UUID.randomUUID());
        resumeReq.setAttemptId(attemptId);
        if (session != null && session.getToken() != null) {
            resumeReq.setToken(session.getToken());
        }

        Map<String, Object> resumePayload = new HashMap<>();
        resumePayload.put("attemptId", attemptId);
        if (userId != null) resumePayload.put("userId", userId);
        ExamTicket ticket = examRegistry.getTicket(attemptId);
        if (ticket != null) {
            resumePayload.put("ticket", ticket);
        }
        resumeReq.setPayload(resumePayload);

        connectionPool.forward(replacement.getServerId(), resumeReq, 5).whenComplete((resumeOk, ex) -> {
            replacement.decrementConnections();
            if (ex != null || resumeOk == null || !resumeOk.isOk()) {
                log.error("Failed to resume attempt {} on replacement Server {}: {}", attemptId, replacement.getServerId(),
                        ex != null ? ex.getMessage() : (resumeOk != null ? resumeOk.getMessage() : "NULL"));
                return;
            }

            if (session != null) {
                session.sendPushMessage(resumeOk);
            }
            log.info("Failover succeeded for attempt {}: remapped to Server {}", attemptId, replacement.getServerId());
        });
    }

    private void failoverNonExamSession(SessionRegistry.ClientSessionEntry session, int deadServerId) {
        List<BackendServerInfo> healthyServers = new ArrayList<>();
        for (BackendServerInfo s : backendServers) {
            if (s.getServerId() != deadServerId && s.getStatus() == ServerStatus.UP) {
                healthyServers.add(s);
            }
        }
        if (!healthyServers.isEmpty()) {
            healthyServers.sort(Comparator.comparingInt(BackendServerInfo::getActiveConnections));
            BackendServerInfo replacement = healthyServers.get(0);
            session.setAssignedServerId(replacement.getServerId());
            log.info("Reassigned non-exam session of user {} from Server {} to Server {}",
                    session.getUsername(), deadServerId, replacement.getServerId());
        }
    }

    public List<String> getDownServerIds() {
        List<String> list = new ArrayList<>();
        for (BackendServerInfo s : backendServers) {
            if (s.getStatus() == ServerStatus.DOWN) {
                list.add("server-" + s.getServerId());
            }
        }
        return list;
    }

    public int getFailoverCount() {
        return failoverCount.get();
    }

    // ==========================================
    // 4. Admin Management Handlers
    // ==========================================

    private void handleAdminStatus(Message req, BufferedWriter writer) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("algorithm", serverSelector.getAlgorithm().name());
        payload.put("totalConnections", activeConnections.get());
        payload.put("queuedStartExams", startExamQueueManager.getQueueLength());
        payload.put("activeStartExams", startExamQueueManager.getActiveCount());
        payload.put("failoverCount", failoverCount.get());
        payload.put("downServers", getDownServerIds());

        List<Map<String, Object>> serversList = new ArrayList<>();
        for (BackendServerInfo s : backendServers) {
            Map<String, Object> sMap = new HashMap<>();
            sMap.put("serverId", s.getServerId());
            sMap.put("host", s.getHost());
            sMap.put("port", s.getPort());
            sMap.put("status", s.getStatus().name());
            sMap.put("activeConnections", s.getActiveConnections());
            sMap.put("baseWeight", s.getBaseWeight());
            sMap.put("effectiveWeight", s.getEffectiveWeight());
            sMap.put("clockSkewMs", s.getClockSkewMs());
            sMap.put("avgResponseTimeMs", s.getAvgResponseTimeMs());
            sMap.put("lastPongTime", s.getLastPongTime());
            serversList.add(sMap);
        }
        payload.put("servers", serversList);

        sendResponse(writer, Message.ok(MessageType.ADMIN_STATUS, req.getRequestId(), payload));
    }

    private void handleAdminSetAlgo(Message req, BufferedWriter writer) {
        if (req.getPayload() != null) {
            try {
                JsonNode node = JsonUtil.readTree(JsonUtil.toJson(req.getPayload()));
                if (node.has("algorithm")) {
                    String algoStr = node.get("algorithm").asText();
                    serverSelector.setAlgorithm(AlgorithmType.valueOf(algoStr.toUpperCase()));
                }
                if (node.has("weights")) {
                    JsonNode weightsNode = node.get("weights");
                    for (BackendServerInfo s : backendServers) {
                        String idKey = String.valueOf(s.getServerId());
                        if (weightsNode.has(idKey)) {
                            s.setBaseWeight(weightsNode.get(idKey).asInt());
                        }
                    }
                }
            } catch (Exception e) {
                sendResponse(writer, Message.error(MessageType.ADMIN_SET_ALGO, req.getRequestId(), ErrorCode.BAD_REQUEST, e.getMessage()));
                return;
            }
        }
        sendResponse(writer, Message.ok(MessageType.ADMIN_SET_ALGO, req.getRequestId(), Map.of(
                "algorithm", serverSelector.getAlgorithm().name()
        )));
    }

    private void handleLogQuery(Message req, BufferedWriter writer) {
        String keyword = null;
        String userId = null;
        int page = 1;
        int pageSize = 20;

        if (req.getPayload() != null) {
            try {
                JsonNode node = JsonUtil.readTree(JsonUtil.toJson(req.getPayload()));
                if (node.has("keyword")) keyword = node.get("keyword").asText();
                if (node.has("userId")) userId = node.get("userId").asText();
                if (node.has("page")) page = node.get("page").asInt();
                if (node.has("pageSize")) pageSize = node.get("pageSize").asInt();
            } catch (Exception ignored) {}
        }

        List<DistributionLogger.LogEntry> logs = distributionLogger.query(keyword, userId, page, pageSize);
        sendResponse(writer, Message.ok(MessageType.LOG_QUERY, req.getRequestId(), Map.of(
                "logs", logs,
                "totalRows", distributionLogger.getTotalCount(),
                "page", page,
                "pageSize", pageSize
        )));
    }

    private void sendResponse(BufferedWriter writer, Message message) {
        try {
            if (writer != null) {
                writer.write(JsonUtil.toJson(message) + "\n");
                writer.flush();
            }
        } catch (IOException e) {
            log.warn("Failed to write response to client: {}", e.getMessage());
        }
    }

    // ==========================================
    // 5. Getters & Configuration Helpers
    // ==========================================

    public List<BackendServerInfo> getBackendServers() {
        return backendServers;
    }

    public BackendServerInfo getBackendServer(int serverId) {
        for (BackendServerInfo s : backendServers) {
            if (s.getServerId() == serverId) return s;
        }
        return null;
    }

    public List<BackendServerInfo> getUpServers() {
        List<BackendServerInfo> up = new ArrayList<>();
        for (BackendServerInfo s : backendServers) {
            if (s.getStatus() == ServerStatus.UP) up.add(s);
        }
        return up;
    }

    public ServerSelector getServerSelector() {
        return serverSelector;
    }

    public SessionRegistry getSessionRegistry() {
        return sessionRegistry;
    }

    public ExamRegistry getExamRegistry() {
        return examRegistry;
    }

    public StartExamQueueManager getStartExamQueueManager() {
        return startExamQueueManager;
    }

    public DistributionLogger getDistributionLogger() {
        return distributionLogger;
    }

    public int getPort() {
        return port;
    }

    public void setMinServersUp(int minServersUp) {
        this.minServersUp = minServersUp;
    }
}
