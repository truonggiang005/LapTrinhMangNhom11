package vn.edu.ptit.exam.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.client.cache.ExamsPageCache;
import vn.edu.ptit.exam.client.event.ExamClientListener;
import vn.edu.ptit.exam.client.net.SocketConnection;
import vn.edu.ptit.exam.client.storage.LocalAttemptData;
import vn.edu.ptit.exam.client.storage.LocalAttemptStorage;
import vn.edu.ptit.exam.common.dto.AnswerItemDto;
import vn.edu.ptit.exam.common.dto.AuthToken;
import vn.edu.ptit.exam.common.dto.BatchUserResultDto;
import vn.edu.ptit.exam.common.dto.CreateExamRequest;
import vn.edu.ptit.exam.common.dto.ExamDto;
import vn.edu.ptit.exam.common.dto.ExamListResponse;
import vn.edu.ptit.exam.common.dto.ExamMonitorDto;
import vn.edu.ptit.exam.common.dto.ExamReadinessDto;
import vn.edu.ptit.exam.common.dto.ExamStatsDto;
import vn.edu.ptit.exam.common.dto.HistoryListResponse;
import vn.edu.ptit.exam.common.dto.LogQueryResponse;
import vn.edu.ptit.exam.common.dto.Message;
import vn.edu.ptit.exam.common.dto.PageResponse;
import vn.edu.ptit.exam.common.dto.QuestionDto;
import vn.edu.ptit.exam.common.dto.QuestionListResponse;
import vn.edu.ptit.exam.common.dto.RetakeRequestDto;
import vn.edu.ptit.exam.common.dto.SaveAnswersResponse;
import vn.edu.ptit.exam.common.dto.ScoreItemDto;
import vn.edu.ptit.exam.common.dto.ScoreListResponse;
import vn.edu.ptit.exam.common.dto.StartExamResponse;
import vn.edu.ptit.exam.common.dto.UserListResponse;
import vn.edu.ptit.exam.common.dto.SubmitResultDto;
import vn.edu.ptit.exam.common.dto.UserDto;
import vn.edu.ptit.exam.common.enums.ErrorCode;
import vn.edu.ptit.exam.common.enums.MessageType;
import vn.edu.ptit.exam.common.util.JsonUtil;

import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Core asynchronous ExamClient library.
 * Pure Java implementation: no JavaFX, non-blocking asynchronous APIs returning CompletableFuture.
 */
public class ExamClient {

    private static final Logger log = LoggerFactory.getLogger(ExamClient.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final int[] RECONNECT_DELAYS = {1, 2, 4, 8, 16, 30};
    private static final int MAX_BUSY_RETRIES = 3;

    private String host;
    private int port;
    private SocketConnection connection;

    private final List<ExamClientListener> listeners = new CopyOnWriteArrayList<>();
    private final ExamsPageCache pageCache = new ExamsPageCache();
    private final LocalAttemptStorage localStorage;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3, r -> {
        Thread t = new Thread(r, "exam-client-scheduler");
        t.setDaemon(true);
        return t;
    });

    // Clock sync
    private volatile long clockOffset = 0L; // serverTime - clientLocalTime
    private final AtomicLong lastPongReceivedTime = new AtomicLong(0L);
    private ScheduledFuture<?> pingTask;

    // Authentication session
    private volatile String sessionToken;
    private volatile AuthToken currentToken;
    private volatile String currentUsername;
    private volatile String currentRole;
    private volatile String currentFullName;

    // Active Exam Session State
    private volatile String activeAttemptId;
    private volatile String activeExamId;
    private volatile String activeExamTitle;
    private volatile String activeSubject;
    private volatile int activeDurationMinutes;
    private volatile long activeDeadlineEpochMs; // Server time
    private final Map<String, AnswerItemDto> answersMap = new ConcurrentHashMap<>();
    private final Set<String> dirtyQuestionIds = ConcurrentHashMap.newKeySet();
    private final AtomicLong currentSeq = new AtomicLong(1L);
    private final AtomicBoolean isExamActive = new AtomicBoolean(false);
    private final AtomicBoolean isSubmitted = new AtomicBoolean(false);
    private final AtomicBoolean isPaused = new AtomicBoolean(false); // during failover
    private volatile SubmitResultDto cachedSubmitResult;

    // Background Exam Threads
    private ScheduledFuture<?> clockTask;
    private ScheduledFuture<?> autoSaveTask;

    // Offline / Reconnection State
    private final AtomicBoolean isReconnecting = new AtomicBoolean(false);
    private final AtomicInteger reconnectCount = new AtomicInteger(0);
    private volatile boolean autoReconnectEnabled = true;

    public ExamClient() {
        this(new LocalAttemptStorage());
    }

    public ExamClient(LocalAttemptStorage localStorage) {
        this.localStorage = localStorage;
    }

    // ==========================================
    // 1. Connection & Lifecycle Management
    // ==========================================

    /**
     * Connect to Load Balancer / Server.
     * Performs 3 PINGs to determine minimum RTT and calculate clock offset.
     * Starts periodic 5s PING keepalive.
     */
    public synchronized CompletableFuture<Void> connect(String targetHost, int targetPort) {
        this.host = targetHost;
        this.port = targetPort;
        this.autoReconnectEnabled = true;

        return CompletableFuture.runAsync(() -> {
            try {
                if (connection != null) {
                    connection.close();
                }
                connection = new SocketConnection(host, port, this::handlePushMessage, this::handleDisconnect);
                connection.connect();
                lastPongReceivedTime.set(System.currentTimeMillis());

                log.info("ExamClient connected to {}:{}", host, port);
                for (ExamClientListener listener : listeners) {
                    listener.onConnected(host, port);
                }

                // Initial Clock Sync: PING 3 times, pick min RTT
                performInitialClockSync();

                // Start periodic 5s PING task
                startPeriodicPing();

            } catch (Exception e) {
                log.error("Failed to connect to {}:{}", host, port, e);
                throw new CompletionException(e);
            }
        });
    }

    public synchronized void disconnect() {
        autoReconnectEnabled = false;
        stopPeriodicPing();
        stopExamBackgroundTasks();

        if (connection != null) {
            connection.close();
            connection = null;
        }
        for (ExamClientListener listener : listeners) {
            listener.onDisconnected("User disconnected");
        }
    }

    public boolean isConnected() {
        return connection != null && connection.isConnected();
    }

    public long getClockOffset() {
        return clockOffset;
    }

    public long getServerTime() {
        return System.currentTimeMillis() + clockOffset;
    }

    public void addListener(ExamClientListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(ExamClientListener listener) {
        listeners.remove(listener);
    }

    // ==========================================
    // 2. Clock Sync & Periodic PING
    // ==========================================

    private void performInitialClockSync() {
        long minRtt = Long.MAX_VALUE;
        long bestOffset = 0L;

        for (int i = 0; i < 3; i++) {
            try {
                long tSend = System.currentTimeMillis();
                Message pingReq = Message.request(MessageType.PING, "PING-" + UUID.randomUUID());
                Message pongResp = connection.sendRequest(pingReq, 3).get(3, TimeUnit.SECONDS);
                long tRecv = System.currentTimeMillis();
                long rtt = tRecv - tSend;

                Long serverTime = pongResp.getServerTime();
                if (serverTime != null && rtt < minRtt) {
                    minRtt = rtt;
                    bestOffset = serverTime - (tSend + tRecv) / 2;
                }
                Thread.sleep(50);
            } catch (Exception e) {
                log.warn("Clock sync ping #{} failed: {}", i + 1, e.getMessage());
            }
        }

        if (minRtt < Long.MAX_VALUE) {
            this.clockOffset = bestOffset;
            log.info("Clock synchronized: minRTT={}ms, offset={}ms", minRtt, clockOffset);
        }
    }

    private synchronized void startPeriodicPing() {
        stopPeriodicPing();
        pingTask = scheduler.scheduleAtFixedRate(() -> {
            if (!isConnected()) {
                return;
            }

            // Watchdog: check if PONG missing for > 10s
            if (System.currentTimeMillis() - lastPongReceivedTime.get() > 10_000L) {
                log.warn("PONG missing for over 10 seconds. Triggering reconnect...");
                handleDisconnect("Heartbeat timeout (>10s without PONG)");
                return;
            }

            try {
                long tSend = System.currentTimeMillis();
                Message pingReq = Message.request(MessageType.PING, "PING-" + UUID.randomUUID());
                connection.sendRequest(pingReq, 4).thenAccept(pong -> {
                    long tRecv = System.currentTimeMillis();
                    lastPongReceivedTime.set(tRecv);
                    Long serverTime = pong.getServerTime();
                    if (serverTime != null) {
                        long sampleOffset = serverTime - (tSend + tRecv) / 2;
                        // Smooth clock adjustment to prevent sudden time leaps
                        this.clockOffset = (long) (this.clockOffset * 0.8 + sampleOffset * 0.2);
                    }
                }).exceptionally(ex -> {
                    log.debug("Periodic ping error: {}", ex.getMessage());
                    return null;
                });
            } catch (Exception e) {
                log.debug("Periodic ping exception: {}", e.getMessage());
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    private synchronized void stopPeriodicPing() {
        if (pingTask != null) {
            pingTask.cancel(true);
            pingTask = null;
        }
    }

    // ==========================================
    // 3. Push Messages & Failover Handling
    // ==========================================

    private void handlePushMessage(Message msg) {
        if (msg == null || msg.getType() == null) {
            return;
        }
        log.info("Handling push message: {}", msg.getType());

        try {
            MessageType type = MessageType.valueOf(msg.getType());
            switch (type) {
                case PONG -> lastPongReceivedTime.set(System.currentTimeMillis());
                case QUEUED -> {
                    int pos = 1;
                    int waitSec = 3;
                    if (msg.getPayload() != null) {
                        JsonNode node = JsonUtil.readTree(JsonUtil.toJson(msg.getPayload()));
                        if (node.has("position")) pos = node.get("position").asInt();
                        if (node.has("estimatedWaitSeconds")) waitSec = node.get("estimatedWaitSeconds").asInt();
                    }
                    for (ExamClientListener listener : listeners) {
                        listener.onQueuePosition(pos, waitSec);
                    }
                }
                case SERVER_SWITCHING -> {
                    String reason = msg.getMessage() != null ? msg.getMessage() : "Máy chủ đang chuyển đổi, xin chờ...";
                    isPaused.set(true);
                    for (ExamClientListener listener : listeners) {
                        listener.onServerSwitching(reason);
                    }
                }
                case SESSION_REPLACED -> {
                    String reason = msg.getMessage() != null ? msg.getMessage() : "Tài khoản của bạn đã đăng nhập từ thiết bị khác.";
                    autoReconnectEnabled = false;
                    disconnect();
                    for (ExamClientListener listener : listeners) {
                        listener.onSessionReplaced(reason);
                    }
                }
                case RESUME_OK -> {
                    isPaused.set(false);
                    // Re-send full answers snapshot after resume
                    if (isExamActive.get() && !isSubmitted.get()) {
                        saveAnswers(true);
                    }
                }
                default -> log.debug("Unhandled push message type: {}", type);
            }
        } catch (Exception e) {
            log.warn("Error handling push message: {}", msg, e);
        }
    }

    // ==========================================
    // 4. Offline Detection & Exponential Reconnect
    // ==========================================

    private void handleDisconnect(String reason) {
        log.warn("Connection lost: {}", reason);
        stopPeriodicPing();

        for (ExamClientListener listener : listeners) {
            listener.onDisconnected(reason);
        }

        if (autoReconnectEnabled && !isReconnecting.get()) {
            triggerReconnect();
        }
    }

    private void triggerReconnect() {
        if (!isReconnecting.compareAndSet(false, true)) {
            return;
        }

        reconnectCount.set(0);
        scheduler.execute(this::runReconnectLoop);
    }

    private void runReconnectLoop() {
        while (autoReconnectEnabled && (connection == null || !connection.isConnected())) {
            int attempt = reconnectCount.incrementAndGet();
            int delaySec = RECONNECT_DELAYS[Math.min(attempt - 1, RECONNECT_DELAYS.length - 1)];

            log.info("Reconnecting attempt #{} in {}s...", attempt, delaySec);
            for (ExamClientListener listener : listeners) {
                listener.onReconnecting(attempt, delaySec);
            }

            try {
                Thread.sleep(delaySec * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            if (!autoReconnectEnabled) {
                break;
            }

            try {
                if (connection != null) {
                    connection.close();
                }
                connection = new SocketConnection(host, port, this::handlePushMessage, this::handleDisconnect);
                connection.connect();
                lastPongReceivedTime.set(System.currentTimeMillis());

                log.info("Reconnected successfully on attempt #{}", attempt);
                isReconnecting.set(false);

                for (ExamClientListener listener : listeners) {
                    listener.onConnected(host, port);
                }

                performInitialClockSync();
                startPeriodicPing();

                // If user was logged in, resume session
                if (sessionToken != null) {
                    resumeSession(sessionToken).thenAccept(v -> {
                        log.info("Session resumed after reconnection");
                        if (isExamActive.get() && !isSubmitted.get()) {
                            // Replay all answers snapshot to server
                            saveAnswers(true);
                        }
                    }).exceptionally(ex -> {
                        log.warn("Failed to resume session after reconnect: {}", ex.getMessage());
                        return null;
                    });
                }
                return;
            } catch (Exception e) {
                log.warn("Reconnect attempt #{} failed: {}", attempt, e.getMessage());
            }
        }
        isReconnecting.set(false);
    }

    // ==========================================
    // 5. Request Execution with 429 BUSY Retries
    // ==========================================

    private CompletableFuture<Message> sendWithRetry(Message request) {
        return sendWithRetry(request, 0);
    }

    private CompletableFuture<Message> sendWithRetry(Message request, int retryCount) {
        if (!isConnected()) {
            CompletableFuture<Message> f = new CompletableFuture<>();
            f.completeExceptionally(new ExamClientException(ErrorCode.NO_SERVER_AVAILABLE.getCode(), "Mất kết nối máy chủ. Đang thử kết nối lại..."));
            return f;
        }

        // Attach token if present and not already attached
        if (sessionToken != null && request.getToken() == null) {
            request.setToken(sessionToken);
        }

        return connection.sendRequest(request).thenCompose(resp -> {
            if (resp.getCode() != null && resp.getCode() == ErrorCode.BUSY.getCode()) {
                int retryAfter = resp.getRetryAfter() != null ? resp.getRetryAfter() : 3;
                if (retryCount < MAX_BUSY_RETRIES) {
                    log.warn("Server is BUSY (429). Retrying attempt {}/{} in {}s...", retryCount + 1, MAX_BUSY_RETRIES, retryAfter);
                    CompletableFuture<Message> delayedFuture = new CompletableFuture<>();
                    scheduler.schedule(() -> {
                        sendWithRetry(request, retryCount + 1).whenComplete((res, ex) -> {
                            if (ex != null) delayedFuture.completeExceptionally(ex);
                            else delayedFuture.complete(res);
                        });
                    }, retryAfter, TimeUnit.SECONDS);
                    return delayedFuture;
                } else {
                    throw new ExamClientException(ErrorCode.BUSY.getCode(), "Hệ thống đang bận. Vui lòng thử lại sau.", retryAfter);
                }
            }

            if (resp.isError()) {
                throw new ExamClientException(resp.getCode() != null ? resp.getCode() : 500, resp.getMessage(), resp.getRetryAfter());
            }

            return CompletableFuture.completedFuture(resp);
        });
    }

    // ==========================================
    // 6. Authentication API
    // ==========================================

    public CompletableFuture<String> login(String username, String password) {
        Message req = Message.request(MessageType.LOGIN, UUID.randomUUID().toString());
        Map<String, Object> payload = new HashMap<>();
        payload.put("username", username);
        payload.put("password", password);
        req.setPayload(payload);

        return sendWithRetry(req).thenApply(resp -> {
            this.sessionToken = resp.getToken();
            this.currentUsername = username;
            if (resp.getPayload() != null) {
                try {
                    JsonNode node = JsonUtil.readTree(JsonUtil.toJson(resp.getPayload()));
                    if (node.has("role")) this.currentRole = node.get("role").asText();
                    if (node.has("fullName")) this.currentFullName = node.get("fullName").asText();

                    String uId = node.has("userId") ? node.get("userId").asText() : "";
                    String uName = node.has("username") ? node.get("username").asText() : username;
                    String fName = this.currentFullName != null ? this.currentFullName : "";
                    String rStr = this.currentRole != null ? this.currentRole : "STUDENT";
                    vn.edu.ptit.exam.common.enums.UserRole uRole;
                    try {
                        uRole = vn.edu.ptit.exam.common.enums.UserRole.valueOf(rStr.toUpperCase());
                    } catch (Exception e) {
                        uRole = vn.edu.ptit.exam.common.enums.UserRole.STUDENT;
                    }
                    this.currentToken = new AuthToken(uId, uName, fName, uRole, System.currentTimeMillis(), System.currentTimeMillis() + 14400000L);
                } catch (Exception ignored) {
                }
            }
            log.info("User {} logged in successfully as {}", username, currentRole);
            return this.sessionToken;
        });
    }

    public CompletableFuture<Void> resumeSession(String tokenStr) {
        Message req = Message.request(MessageType.RESUME_SESSION, UUID.randomUUID().toString());
        req.setToken(tokenStr);

        return sendWithRetry(req).thenAccept(resp -> {
            log.info("RESUME_SESSION confirmed by server");
        });
    }

    public void logout() {
        sessionToken = null;
        currentToken = null;
        currentUsername = null;
        currentRole = null;
        currentFullName = null;
        pageCache.invalidateAll();
        disconnect();
    }

    public String getSessionToken() {
        return sessionToken;
    }

    public AuthToken getCurrentToken() {
        return currentToken;
    }

    public String getCurrentUsername() {
        return currentUsername;
    }

    public String getCurrentRole() {
        return currentRole;
    }

    public String getCurrentFullName() {
        return currentFullName;
    }

    // ==========================================
    // 7. Student Exam Operations
    // ==========================================

    /**
     * List assigned exams with 30-second in-memory page cache.
     */
    public CompletableFuture<ExamListResponse> listExams(String keyword, String status, int page, int pageSize, boolean forceRefresh) {
        if (!forceRefresh) {
            ExamListResponse cached = pageCache.get(keyword, status, page, pageSize);
            if (cached != null) {
                log.debug("Returning cached LIST_EXAMS page key ({}, {}, {}, {})", keyword, status, page, pageSize);
                return CompletableFuture.completedFuture(cached);
            }
        }

        Message req = Message.request(MessageType.LIST_EXAMS, UUID.randomUUID().toString());
        Map<String, Object> payload = new HashMap<>();
        if (keyword != null && !keyword.isBlank()) payload.put("keyword", keyword.trim());
        if (status != null && !status.isBlank()) payload.put("status", status.trim());
        payload.put("page", page);
        payload.put("pageSize", pageSize);
        req.setPayload(payload);

        return sendWithRetry(req).thenApply(resp -> {
            ExamListResponse listResp = JsonUtil.convertValue(resp.getPayload(), ExamListResponse.class);
            if (listResp != null) {
                pageCache.put(keyword, status, page, pageSize, listResp);
            }
            return listResp;
        });
    }

    /**
     * Start or resume an exam attempt. Handles QUEUED responses and launches background tasks.
     */
    public CompletableFuture<StartExamResponse> startExam(String examId) {
        Message req = Message.request(MessageType.START_EXAM, UUID.randomUUID().toString());
        Map<String, Object> payload = new HashMap<>();
        payload.put("examId", examId);
        req.setPayload(payload);

        return sendWithRetry(req).thenApply(resp -> {
            StartExamResponse startResp = JsonUtil.convertValue(resp.getPayload(), StartExamResponse.class);
            if (startResp == null) {
                throw new ExamClientException(500, "Phản hồi rỗng từ máy chủ khi vào thi");
            }

            setupActiveExamSession(startResp);
            return startResp;
        });
    }

    private void setupActiveExamSession(StartExamResponse startResp) {
        this.activeAttemptId = startResp.getAttemptId();
        this.activeExamId = startResp.getExamId();
        this.activeExamTitle = startResp.getExamTitle();
        this.activeSubject = startResp.getSubject();
        this.activeDurationMinutes = startResp.getDurationMinutes();
        this.activeDeadlineEpochMs = startResp.getDeadline();
        this.answersMap.clear();
        this.dirtyQuestionIds.clear();
        this.currentSeq.set(1L);
        this.isExamActive.set(true);
        this.isSubmitted.set(false);
        this.cachedSubmitResult = null;

        // Restore previously answered questions if recovered
        if (startResp.getQuestions() != null) {
            for (QuestionDto q : startResp.getQuestions()) {
                if (q.getSelectedOptionId() != null || q.isMarked()) {
                    AnswerItemDto item = new AnswerItemDto(q.getId(), q.getSelectedOptionId(), q.isMarked());
                    answersMap.put(q.getId(), item);
                }
            }
        }

        // Save local snapshot
        persistLocalSnapshot();

        // Launch 3 background tasks: Clock timer, auto-save, notification listener
        startExamBackgroundTasks();

        pageCache.invalidateAll();
        log.info("Active exam session initialized: attemptId={}, deadline={}", activeAttemptId, activeDeadlineEpochMs);
    }

    /**
     * Set/update student answer for a question.
     * Updates in-memory state, sets dirty flag, and asynchronously saves snapshot to local disk.
     */
    public void setAnswer(String questionId, String optionId, boolean isMarked) {
        if (!isExamActive.get() || isSubmitted.get()) {
            return;
        }

        AnswerItemDto item = new AnswerItemDto(questionId, optionId, isMarked);
        answersMap.put(questionId, item);
        dirtyQuestionIds.add(questionId);

        // Async save to local disk
        persistLocalSnapshot();
    }

    /**
     * Hook called when user navigates from one question to another in UI.
     * Triggers an immediate auto-save if any questions are dirty.
     */
    public void onQuestionChanged(String previousQuestionId, String currentQuestionId) {
        if (!dirtyQuestionIds.isEmpty() && isConnected() && !isPaused.get()) {
            saveAnswers(false);
        }
    }

    /**
     * Save answers to server.
     * @param force If true, sends all answers regardless of dirty flags (e.g. before SUBMIT or after reconnect).
     */
    public CompletableFuture<SaveAnswersResponse> saveAnswers(boolean force) {
        if (!isExamActive.get() || isSubmitted.get()) {
            return CompletableFuture.completedFuture(new SaveAnswersResponse(0, getServerTime(), 0));
        }

        List<AnswerItemDto> answersToSend = new ArrayList<>();
        if (force) {
            answersToSend.addAll(answersMap.values());
        } else {
            for (String qId : dirtyQuestionIds) {
                AnswerItemDto item = answersMap.get(qId);
                if (item != null) {
                    answersToSend.add(item);
                }
            }
        }

        if (answersToSend.isEmpty()) {
            return CompletableFuture.completedFuture(new SaveAnswersResponse(0, getServerTime(), 0));
        }

        long seq = currentSeq.getAndIncrement();
        Message req = Message.request(MessageType.SAVE_ANSWERS, UUID.randomUUID().toString());
        req.setAttemptId(activeAttemptId);
        req.setSeq(seq);

        Map<String, Object> payload = new HashMap<>();
        payload.put("attemptId", activeAttemptId);
        payload.put("seq", seq);
        payload.put("answers", answersToSend);
        req.setPayload(payload);

        notifySaveStatus("Đang lưu...", false);

        return sendWithRetry(req).thenApply(resp -> {
            SaveAnswersResponse saveResp = JsonUtil.convertValue(resp.getPayload(), SaveAnswersResponse.class);
            // Clear dirty flags for sent answers
            for (AnswerItemDto item : answersToSend) {
                dirtyQuestionIds.remove(item.getQuestionId());
            }

            String timeStr = LocalTime.now().format(TIME_FORMATTER);
            notifySaveStatus("Đã lưu lúc " + timeStr, false);
            return saveResp;
        }).exceptionally(ex -> {
            log.warn("saveAnswers failed: {}", ex.getMessage());
            notifySaveStatus("Chưa lưu – đang thử lại", true);

            // Check if 410 ATTEMPT_EXPIRED
            Throwable cause = ex instanceof CompletionException ? ex.getCause() : ex;
            if (cause instanceof ExamClientException ece && ece.getErrorCode() == ErrorCode.ATTEMPT_EXPIRED.getCode()) {
                log.info("Received 410 ATTEMPT_EXPIRED from server. Transitioning to auto-submit...");
                submit(activeAttemptId);
            }
            return new SaveAnswersResponse(0, getServerTime(), 0);
        });
    }

    /**
     * Submit exam attempt. Idempotent: uses deterministic requestId "SUBMIT-<attemptId>".
     * Sends full snapshot before submitting to ensure zero unpersisted answers.
     */
    public synchronized CompletableFuture<SubmitResultDto> submit(String attemptId) {
        if (cachedSubmitResult != null) {
            return CompletableFuture.completedFuture(cachedSubmitResult);
        }

        isSubmitted.set(true);
        stopExamBackgroundTasks();

        // 1. Try sending full snapshot first if connected
        CompletableFuture<Void> preSave = isConnected() ?
                saveAnswers(true).thenApply(r -> (Void) null).exceptionally(e -> null) :
                CompletableFuture.completedFuture(null);

        return preSave.thenCompose(v -> {
            // Strict Invariant 4: deterministic requestId
            String reqId = "SUBMIT-" + attemptId;
            Message req = Message.request(MessageType.SUBMIT, reqId);
            req.setAttemptId(attemptId);
            Map<String, Object> payload = new HashMap<>();
            payload.put("attemptId", attemptId);
            req.setPayload(payload);

            return sendWithRetry(req).thenApply(resp -> {
                SubmitResultDto result = JsonUtil.convertValue(resp.getPayload(), SubmitResultDto.class);
                this.cachedSubmitResult = result;
                this.isExamActive.set(false);

                // Clean up local disk storage
                localStorage.deleteAttempt(attemptId);
                pageCache.invalidateAll();

                log.info("Exam submitted successfully: attemptId={}, score={}", attemptId, result != null ? result.getScore() : null);
                return result;
            });
        });
    }

    public CompletableFuture<SubmitResultDto> getResult(String attemptId) {
        Message req = Message.request(MessageType.GET_RESULT, UUID.randomUUID().toString());
        req.setAttemptId(attemptId);
        Map<String, Object> payload = new HashMap<>();
        payload.put("attemptId", attemptId);
        req.setPayload(payload);

        return sendWithRetry(req).thenApply(resp -> JsonUtil.convertValue(resp.getPayload(), SubmitResultDto.class));
    }

    public CompletableFuture<HistoryListResponse> listHistory(int page, int pageSize) {
        Message req = Message.request(MessageType.LIST_HISTORY, UUID.randomUUID().toString());
        Map<String, Object> payload = new HashMap<>();
        payload.put("page", page);
        payload.put("pageSize", pageSize);
        req.setPayload(payload);

        return sendWithRetry(req).thenApply(resp -> JsonUtil.convertValue(resp.getPayload(), HistoryListResponse.class));
    }

    // ==========================================
    // 8. Exam Background Tasks (Clock & Auto-Save)
    // ==========================================

    private synchronized void startExamBackgroundTasks() {
        stopExamBackgroundTasks();

        // Task 1: Clock Countdown Timer (1s)
        clockTask = scheduler.scheduleAtFixedRate(() -> {
            if (!isExamActive.get() || isSubmitted.get()) {
                return;
            }

            long currentServerTime = getServerTime();
            long remainingSec = Math.max(0, (activeDeadlineEpochMs - currentServerTime) / 1000L);

            for (ExamClientListener listener : listeners) {
                listener.onClockTick(remainingSec);
            }

            if (remainingSec == 300) {
                for (ExamClientListener listener : listeners) {
                    listener.onTimeWarning("Còn 5 phút kết thúc bài thi!", remainingSec);
                }
            } else if (remainingSec == 60) {
                for (ExamClientListener listener : listeners) {
                    listener.onTimeWarning("Còn 1 phút kết thúc bài thi!", remainingSec);
                }
            } else if (remainingSec <= 0) {
                log.info("Time is UP for attempt {}. Triggering auto-submit...", activeAttemptId);
                for (ExamClientListener listener : listeners) {
                    listener.onTimeUp();
                }
                submit(activeAttemptId);
            }
        }, 1, 1, TimeUnit.SECONDS);

        // Task 2: Auto-Save Worker (every 20s)
        autoSaveTask = scheduler.scheduleAtFixedRate(() -> {
            if (isExamActive.get() && !isSubmitted.get() && !isPaused.get() && isConnected()) {
                if (!dirtyQuestionIds.isEmpty()) {
                    saveAnswers(false);
                }
            }
        }, 20, 20, TimeUnit.SECONDS);
    }

    private synchronized void stopExamBackgroundTasks() {
        if (clockTask != null) {
            clockTask.cancel(true);
            clockTask = null;
        }
        if (autoSaveTask != null) {
            autoSaveTask.cancel(true);
            autoSaveTask = null;
        }
    }

    private void persistLocalSnapshot() {
        if (activeAttemptId == null) {
            return;
        }
        LocalAttemptData snapshot = new LocalAttemptData(
                activeAttemptId, activeExamId, activeExamTitle, activeSubject,
                activeDurationMinutes, 0L, activeDeadlineEpochMs,
                sessionToken
        );
        snapshot.setAnswers(new HashMap<>(answersMap));
        localStorage.saveAttemptAsync(snapshot);
    }

    private void notifySaveStatus(String statusText, boolean isError) {
        for (ExamClientListener listener : listeners) {
            listener.onSaveStatusChanged(statusText, isError);
        }
    }

    // ==========================================
    // 9. Lecturer & Admin Operations
    // ==========================================

    public CompletableFuture<QuestionListResponse> listQuestions(String keyword, String subject, Integer difficulty, int page, int pageSize) {
        String diffStr = difficulty != null ? String.valueOf(difficulty) : null;
        return listQuestions(keyword, subject, null, diffStr, page, pageSize);
    }

    public CompletableFuture<QuestionListResponse> listQuestions(String keyword, String subject, Integer chapter, String difficulty, int page, int pageSize) {
        Message req = Message.request(MessageType.QUESTION_LIST, UUID.randomUUID().toString());
        Map<String, Object> payload = new HashMap<>();
        if (keyword != null) payload.put("keyword", keyword);
        if (subject != null) payload.put("subject", subject);
        if (chapter != null) payload.put("chapter", chapter);
        if (difficulty != null) payload.put("difficulty", difficulty);
        payload.put("page", page);
        payload.put("pageSize", pageSize);
        req.setPayload(payload);

        return sendWithRetry(req).thenApply(resp -> JsonUtil.convertValue(resp.getPayload(), QuestionListResponse.class));
    }

    public CompletableFuture<QuestionDto> createQuestion(QuestionDto question) {
        Message req = Message.request(MessageType.QUESTION_CREATE, UUID.randomUUID().toString());
        req.setPayload(question);
        return sendWithRetry(req).thenApply(resp -> JsonUtil.convertValue(resp.getPayload(), QuestionDto.class));
    }

    public CompletableFuture<QuestionDto> updateQuestion(QuestionDto question, int baseVersion) {
        Message req = Message.request(MessageType.QUESTION_UPDATE, UUID.randomUUID().toString());
        question.setVersion(baseVersion);
        req.setPayload(question);
        return sendWithRetry(req).thenApply(resp -> JsonUtil.convertValue(resp.getPayload(), QuestionDto.class));
    }

    public CompletableFuture<Void> deleteQuestion(String questionId, int baseVersion) {
        Message req = Message.request(MessageType.QUESTION_DELETE, UUID.randomUUID().toString());
        Map<String, Object> payload = new HashMap<>();
        payload.put("questionId", questionId);
        payload.put("baseVersion", baseVersion);
        req.setPayload(payload);
        return sendWithRetry(req).thenAccept(resp -> {});
    }

    public CompletableFuture<ExamDto> createExam(ExamDto exam) {
        Message req = Message.request(MessageType.EXAM_CREATE, UUID.randomUUID().toString());
        req.setPayload(exam);
        return sendWithRetry(req).thenApply(resp -> JsonUtil.convertValue(resp.getPayload(), ExamDto.class));
    }

    public CompletableFuture<ExamDto> createExam(CreateExamRequest examRequest) {
        Message req = Message.request(MessageType.EXAM_CREATE, UUID.randomUUID().toString());
        req.setPayload(examRequest);
        return sendWithRetry(req).thenApply(resp -> JsonUtil.convertValue(resp.getPayload(), ExamDto.class));
    }

    public CompletableFuture<ExamReadinessDto> getExamReadiness(String examId) {
        Message req = Message.request(MessageType.EXAM_MONITOR, UUID.randomUUID().toString());
        Map<String, Object> payload = new HashMap<>();
        payload.put("examId", examId);
        payload.put("checkReadiness", true);
        req.setPayload(payload);
        return sendWithRetry(req).thenApply(resp -> JsonUtil.convertValue(resp.getPayload(), ExamReadinessDto.class));
    }

    public CompletableFuture<ExamMonitorDto> getExamMonitor(String examId) {
        Message req = Message.request(MessageType.EXAM_MONITOR, UUID.randomUUID().toString());
        Map<String, Object> payload = new HashMap<>();
        payload.put("examId", examId);
        req.setPayload(payload);
        return sendWithRetry(req).thenApply(resp -> JsonUtil.convertValue(resp.getPayload(), ExamMonitorDto.class));
    }

    public CompletableFuture<ScoreListResponse> getScores(String examId, String keyword, int page, int pageSize, String sort) {
        Message req = Message.request(MessageType.GET_SCORES, UUID.randomUUID().toString());
        Map<String, Object> payload = new HashMap<>();
        payload.put("examId", examId);
        if (keyword != null) payload.put("keyword", keyword);
        payload.put("page", page);
        payload.put("pageSize", pageSize);
        if (sort != null) payload.put("sort", sort);
        req.setPayload(payload);

        return sendWithRetry(req).thenApply(resp -> JsonUtil.convertValue(resp.getPayload(), ScoreListResponse.class));
    }

    public CompletableFuture<ExamStatsDto> getStats(String examId) {
        Message req = Message.request(MessageType.GET_STATS, UUID.randomUUID().toString());
        Map<String, Object> payload = new HashMap<>();
        payload.put("examId", examId);
        req.setPayload(payload);
        return sendWithRetry(req).thenApply(resp -> JsonUtil.convertValue(resp.getPayload(), ExamStatsDto.class));
    }

    // --- Retake Request APIs ---

    public CompletableFuture<RetakeRequestDto> requestRetake(String examId, String reason) {
        Message req = Message.request(MessageType.EXAM_RETAKE_REQUEST, UUID.randomUUID().toString());
        Map<String, Object> payload = new HashMap<>();
        payload.put("examId", examId);
        payload.put("reason", reason);
        req.setPayload(payload);
        return sendWithRetry(req).thenApply(resp -> JsonUtil.convertValue(resp.getPayload(), RetakeRequestDto.class));
    }

    public CompletableFuture<List<RetakeRequestDto>> listRetakeRequests(String examId, String statusFilter) {
        Message req = Message.request(MessageType.EXAM_RETAKE_LIST, UUID.randomUUID().toString());
        Map<String, Object> payload = new HashMap<>();
        if (examId != null) payload.put("examId", examId);
        if (statusFilter != null) payload.put("status", statusFilter);
        req.setPayload(payload);
        return sendWithRetry(req).thenApply(resp -> {
            if (resp.getPayload() == null) return Collections.emptyList();
            return JsonUtil.convertValue(resp.getPayload(), new com.fasterxml.jackson.core.type.TypeReference<List<RetakeRequestDto>>() {});
        });
    }

    public CompletableFuture<Void> decideRetakeRequest(String requestId, String action) {
        Message req = Message.request(MessageType.EXAM_RETAKE_DECIDE, UUID.randomUUID().toString());
        Map<String, Object> payload = new HashMap<>();
        payload.put("requestId", requestId);
        payload.put("action", action);
        req.setPayload(payload);
        return sendWithRetry(req).thenAccept(resp -> {});
    }

    // --- Admin User Management APIs ---

    public CompletableFuture<UserDto> createUser(UserDto user) {
        Message req = Message.request(MessageType.USER_CREATE, UUID.randomUUID().toString());
        req.setPayload(user);
        return sendWithRetry(req).thenApply(resp -> JsonUtil.convertValue(resp.getPayload(), UserDto.class));
    }

    public CompletableFuture<UserDto> updateUser(UserDto user) {
        Message req = Message.request(MessageType.USER_UPDATE, UUID.randomUUID().toString());
        req.setPayload(user);
        return sendWithRetry(req).thenApply(resp -> JsonUtil.convertValue(resp.getPayload(), UserDto.class));
    }

    public CompletableFuture<Void> resetUserPassword(String userId, String newPassword) {
        Message req = Message.request(MessageType.USER_RESET_PASSWORD, UUID.randomUUID().toString());
        Map<String, Object> payload = new HashMap<>();
        payload.put("userId", userId);
        payload.put("newPassword", newPassword);
        req.setPayload(payload);
        return sendWithRetry(req).thenAccept(resp -> {});
    }

    public CompletableFuture<Void> setUserLocked(String userId, boolean locked) {
        Message req = Message.request(MessageType.USER_LOCK, UUID.randomUUID().toString());
        Map<String, Object> payload = new HashMap<>();
        payload.put("userId", userId);
        payload.put("locked", locked);
        req.setPayload(payload);
        return sendWithRetry(req).thenAccept(resp -> {});
    }

    public CompletableFuture<BatchUserResultDto> importUsersBatch(List<UserDto> users) {
        Message req = Message.request(MessageType.USER_BATCH, UUID.randomUUID().toString());
        Map<String, Object> payload = new HashMap<>();
        payload.put("users", users);
        req.setPayload(payload);
        return sendWithRetry(req).thenApply(resp -> JsonUtil.convertValue(resp.getPayload(), BatchUserResultDto.class));
    }

    public CompletableFuture<UserListResponse> listUsers(String keyword, String role, int page, int pageSize) {
        Message req = Message.request(MessageType.USER_LIST, UUID.randomUUID().toString());
        Map<String, Object> payload = new HashMap<>();
        if (keyword != null) payload.put("keyword", keyword);
        if (role != null) payload.put("role", role);
        payload.put("page", page);
        payload.put("pageSize", pageSize);
        req.setPayload(payload);
        return sendWithRetry(req).thenApply(resp -> JsonUtil.convertValue(resp.getPayload(), UserListResponse.class));
    }

    @SuppressWarnings("unchecked")
    public CompletableFuture<Map<String, Object>> getAdminStatus() {
        Message req = Message.request(MessageType.ADMIN_STATUS, UUID.randomUUID().toString());
        return sendWithRetry(req).thenApply(resp -> {
            if (resp.getPayload() instanceof Map) {
                return (Map<String, Object>) resp.getPayload();
            }
            return Collections.emptyMap();
        });
    }

    @SuppressWarnings("unchecked")
    public CompletableFuture<Map<String, Object>> setAdminAlgorithm(String algorithm, Map<String, Integer> weights) {
        Message req = Message.request(MessageType.ADMIN_SET_ALGO, UUID.randomUUID().toString());
        Map<String, Object> payload = new HashMap<>();
        if (algorithm != null) payload.put("algorithm", algorithm);
        if (weights != null) payload.put("weights", weights);
        req.setPayload(payload);
        return sendWithRetry(req).thenApply(resp -> {
            if (resp.getPayload() instanceof Map) {
                return (Map<String, Object>) resp.getPayload();
            }
            return Collections.emptyMap();
        });
    }

    // --- Admin Log Query API ---

    public CompletableFuture<LogQueryResponse> queryLogs(String logType, String keyword, Long startTime, Long endTime, int page, int pageSize) {
        Message req = Message.request(MessageType.LOG_QUERY, UUID.randomUUID().toString());
        Map<String, Object> payload = new HashMap<>();
        if (logType != null) payload.put("logType", logType);
        if (keyword != null) payload.put("keyword", keyword);
        if (startTime != null) payload.put("startTime", startTime);
        if (endTime != null) payload.put("endTime", endTime);
        payload.put("page", page);
        payload.put("pageSize", pageSize);
        req.setPayload(payload);
        return sendWithRetry(req).thenApply(resp -> JsonUtil.convertValue(resp.getPayload(), LogQueryResponse.class));
    }

    /**
     * Section 9 / Luồng 7: Generic helper to fetch all items across pages
     * sequentially (chunk size 200) without blocking the thread, reporting progress via callback.
     */
    public <T> CompletableFuture<List<T>> fetchAll(
            BiFunction<Integer, Integer, CompletableFuture<PageResponse<T>>> pageFetcher,
            Consumer<Double> progressCallback) {
        List<T> accumulator = new ArrayList<>();
        int pageSize = 200;
        return fetchAllRecursive(pageFetcher, 1, pageSize, accumulator, progressCallback);
    }

    private <T> CompletableFuture<List<T>> fetchAllRecursive(
            BiFunction<Integer, Integer, CompletableFuture<PageResponse<T>>> pageFetcher,
            int page, int pageSize,
            List<T> accumulator,
            Consumer<Double> progressCallback) {
        return pageFetcher.apply(page, pageSize).thenCompose(resp -> {
            if (resp == null || resp.getItems() == null || resp.getItems().isEmpty()) {
                if (progressCallback != null) progressCallback.accept(1.0);
                return CompletableFuture.completedFuture(accumulator);
            }
            accumulator.addAll(resp.getItems());
            int total = resp.getTotalRows();
            if (progressCallback != null && total > 0) {
                progressCallback.accept(Math.min(1.0, (double) accumulator.size() / total));
            }
            if (accumulator.size() >= total || resp.getItems().size() < pageSize) {
                if (progressCallback != null) progressCallback.accept(1.0);
                return CompletableFuture.completedFuture(accumulator);
            }
            return fetchAllRecursive(pageFetcher, page + 1, pageSize, accumulator, progressCallback);
        });
    }

    /**
     * Section 9 / Luồng 7: Fetch all score rows page by page (200 rows per page)
     * and reports progress via progressCallback (0.0 to 1.0) for CSV export.
     */
    public CompletableFuture<List<ScoreItemDto>> fetchAllScores(String examId, Consumer<Double> progressCallback) {
        List<ScoreItemDto> allScores = new ArrayList<>();
        int pageSize = 200;

        return fetchScoresPageRecursive(examId, 1, pageSize, allScores, progressCallback);
    }

    private CompletableFuture<List<ScoreItemDto>> fetchScoresPageRecursive(
            String examId, int page, int pageSize,
            List<ScoreItemDto> accumulator,
            Consumer<Double> progressCallback) {

        return getScores(examId, null, page, pageSize, "studentCode")
                .thenCompose(response -> {
                    if (response == null || response.getScores() == null || response.getScores().isEmpty()) {
                        if (progressCallback != null) progressCallback.accept(1.0);
                        return CompletableFuture.completedFuture(accumulator);
                    }

                    accumulator.addAll(response.getScores());
                    int totalRows = response.getTotalRows();
                    if (progressCallback != null && totalRows > 0) {
                        double progress = Math.min(1.0, (double) accumulator.size() / totalRows);
                        progressCallback.accept(progress);
                    }

                    if (accumulator.size() >= totalRows || response.getScores().size() < pageSize) {
                        if (progressCallback != null) progressCallback.accept(1.0);
                        return CompletableFuture.completedFuture(accumulator);
                    }

                    return fetchScoresPageRecursive(examId, page + 1, pageSize, accumulator, progressCallback);
                });
    }

    // ==========================================
    // 10. Offline / Recovery Helpers
    // ==========================================

    public Optional<LocalAttemptData> checkIncompleteAttempt() {
        return localStorage.findLatestIncompleteAttempt();
    }

    public LocalAttemptStorage getLocalStorage() {
        return localStorage;
    }

    public Map<String, AnswerItemDto> getAnswersMap() {
        return answersMap;
    }

    public Set<String> getDirtyQuestionIds() {
        return dirtyQuestionIds;
    }
}
