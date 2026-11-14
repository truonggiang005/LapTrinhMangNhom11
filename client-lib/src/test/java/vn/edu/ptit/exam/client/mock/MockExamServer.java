package vn.edu.ptit.exam.client.mock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.common.dto.Message;
import vn.edu.ptit.exam.common.enums.MessageType;
import vn.edu.ptit.exam.common.util.JsonUtil;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Lightweight mock server for client-lib unit and integration tests.
 * Runs pure TCP socket matching the protocol, allowing simulation of:
 * - Normal responses (PING, LOGIN, LIST_EXAMS, START_EXAM, SAVE_ANSWERS, SUBMIT)
 * - Clock sync with custom serverTime
 * - Deliberate delays to test timeouts
 * - Sudden socket disconnections
 * - 429 BUSY throttling
 * - Push notifications (QUEUED, SERVER_SWITCHING, SESSION_REPLACED)
 */
public class MockExamServer {

    private static final Logger log = LoggerFactory.getLogger(MockExamServer.class);

    private final ServerSocket serverSocket;
    private final int port;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final List<Socket> clientSockets = new CopyOnWriteArrayList<>();
    private final List<BufferedWriter> clientWriters = new CopyOnWriteArrayList<>();
    private final List<Message> receivedRequests = new CopyOnWriteArrayList<>();

    private final Map<String, Function<Message, Message>> customHandlers = new ConcurrentHashMap<>();
    private volatile long simulatedServerTimeOffset = 5000L; // 5s in the future
    private volatile long responseDelayMs = 0L;
    private volatile boolean dropConnectionOnNextRequest = false;

    public MockExamServer() throws IOException {
        this.serverSocket = new ServerSocket(0); // auto-assigned ephemeral port
        this.port = serverSocket.getLocalPort();
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            Thread acceptThread = new Thread(this::runAcceptLoop, "mock-server-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            log.info("MockExamServer started on port {}", port);
        }
    }

    public void stop() {
        running.set(false);
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
        for (Socket s : clientSockets) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        clientSockets.clear();
        clientWriters.clear();
    }

    public int getPort() {
        return port;
    }

    public List<Message> getReceivedRequests() {
        return receivedRequests;
    }

    public void setCustomHandler(String messageType, Function<Message, Message> handler) {
        customHandlers.put(messageType, handler);
    }

    public void setSimulatedServerTimeOffset(long offsetMs) {
        this.simulatedServerTimeOffset = offsetMs;
    }

    public void setResponseDelayMs(long delayMs) {
        this.responseDelayMs = delayMs;
    }

    public void setDropConnectionOnNextRequest(boolean drop) {
        this.dropConnectionOnNextRequest = drop;
    }

    public void broadcast(Message message) {
        String json = JsonUtil.toJson(message) + "\n";
        for (BufferedWriter writer : clientWriters) {
            try {
                writer.write(json);
                writer.flush();
            } catch (IOException e) {
                log.warn("Failed to broadcast to client: {}", e.getMessage());
            }
        }
    }

    public void disconnectAllClients() {
        for (Socket s : clientSockets) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        clientSockets.clear();
        clientWriters.clear();
    }

    private void runAcceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                clientSockets.add(socket);

                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                clientWriters.add(writer);

                Thread clientThread = new Thread(() -> handleClient(socket, writer), "mock-client-handler-" + socket.getPort());
                clientThread.setDaemon(true);
                clientThread.start();
            } catch (IOException e) {
                if (running.get()) {
                    log.error("MockExamServer accept error", e);
                }
            }
        }
    }

    private void handleClient(Socket socket, BufferedWriter writer) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                Message req = JsonUtil.fromJson(line, Message.class);
                if (req == null) continue;
                receivedRequests.add(req);

                if (dropConnectionOnNextRequest) {
                    log.info("Simulating abrupt connection drop");
                    socket.close();
                    return;
                }

                if (responseDelayMs > 0) {
                    try {
                        Thread.sleep(responseDelayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }

                Message resp = generateDefaultResponse(req);
                if (customHandlers.containsKey(req.getType())) {
                    resp = customHandlers.get(req.getType()).apply(req);
                }

                if (resp != null) {
                    if (resp.getServerTime() == null) {
                        resp.setServerTime(System.currentTimeMillis() + simulatedServerTimeOffset);
                    }
                    writer.write(JsonUtil.toJson(resp) + "\n");
                    writer.flush();
                }
            }
        } catch (IOException e) {
            log.debug("Client socket closed: {}", e.getMessage());
        } finally {
            clientSockets.remove(socket);
            clientWriters.remove(writer);
        }
    }

    public Message generateDefaultResponse(Message req) {
        if (req.getType() == null) {
            return Message.error("UNKNOWN", req.getRequestId(), vn.edu.ptit.exam.common.enums.ErrorCode.BAD_REQUEST);
        }

        long now = System.currentTimeMillis() + simulatedServerTimeOffset;
        MessageType type;
        try {
            type = MessageType.valueOf(req.getType());
        } catch (Exception e) {
            return Message.ok(req.getType(), req.getRequestId(), Map.of());
        }

        switch (type) {
            case PING -> {
                Message pong = Message.ok(MessageType.PONG, req.getRequestId(), Map.of("load", 1));
                pong.setServerTime(now);
                return pong;
            }
            case LOGIN -> {
                Message resp = Message.ok(MessageType.LOGIN, req.getRequestId(), Map.of(
                        "username", "student01",
                        "fullName", "Nguyen Van A",
                        "role", "STUDENT",
                        "serverTime", now
                ));
                resp.setToken("mock-valid-hmac-token");
                resp.setServerTime(now);
                return resp;
            }
            case RESUME_SESSION -> {
                Message resp = Message.ok(MessageType.RESUME_SESSION, req.getRequestId(), Map.of("status", "RESUME_OK"));
                resp.setServerTime(now);
                return resp;
            }
            case LIST_EXAMS -> {
                Map<String, Object> payload = Map.of(
                        "exams", List.of(Map.of("id", "1", "title", "Thi giữa kỳ Mạng", "status", "OPEN")),
                        "totalRows", 1,
                        "page", 1,
                        "pageSize", 10
                );
                Message resp = Message.ok(MessageType.LIST_EXAMS, req.getRequestId(), payload);
                resp.setServerTime(now);
                return resp;
            }
            case START_EXAM -> {
                Map<String, Object> payload = Map.of(
                        "attemptId", "101",
                        "examId", "1",
                        "examTitle", "Thi giữa kỳ Mạng",
                        "subject", "Lập trình mạng",
                        "durationMinutes", 45,
                        "startTime", now,
                        "deadline", now + 45 * 60 * 1000L,
                        "serverTime", now,
                        "remainingSeconds", 45 * 60,
                        "questions", List.of(
                                Map.of("id", "q1", "orderNum", 1, "content", "Giao thức TCP là gì?",
                                        "options", List.of(Map.of("id", "o1", "optionKey", "A", "content", "Tin cậy")))
                        )
                );
                Message resp = Message.ok(MessageType.START_EXAM, req.getRequestId(), payload);
                resp.setServerTime(now);
                return resp;
            }
            case SAVE_ANSWERS -> {
                Message resp = Message.ok(MessageType.SAVE_ANSWERS, req.getRequestId(), Map.of(
                        "savedCount", 1,
                        "serverTime", now,
                        "remainingSeconds", 2000
                ));
                resp.setAttemptId(req.getAttemptId());
                resp.setSeq(req.getSeq());
                resp.setServerTime(now);
                return resp;
            }
            case SUBMIT -> {
                Message resp = Message.ok(MessageType.SUBMIT, req.getRequestId(), Map.of(
                        "attemptId", req.getAttemptId() != null ? req.getAttemptId() : "101",
                        "status", "SUBMITTED",
                        "score", 9.5,
                        "correctCount", 19,
                        "totalQuestions", 20,
                        "isLate", false,
                        "submittedAt", now
                ));
                resp.setServerTime(now);
                return resp;
            }
            default -> {
                Message resp = Message.ok(req.getType(), req.getRequestId(), Map.of());
                resp.setServerTime(now);
                return resp;
            }
        }
    }
}
