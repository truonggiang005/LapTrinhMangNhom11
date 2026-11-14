package vn.edu.ptit.exam.server.lb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.common.dto.Message;
import vn.edu.ptit.exam.common.enums.ErrorCode;
import vn.edu.ptit.exam.common.enums.MessageType;
import vn.edu.ptit.exam.common.util.JsonUtil;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session Registry on Load Balancer according to Section 3 / Luồng 1.
 * Enforces single active session per user, dual login handling (409 SESSION_IN_EXAM vs SESSION_REPLACED),
 * and session resumption.
 */
public class SessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

    public static class ClientSessionEntry {
        private final String userId;
        private final String username;
        private volatile Socket clientSocket;
        private volatile BufferedWriter clientWriter;
        private volatile int assignedServerId;
        private final long loginTime;
        private volatile boolean inExam;
        private volatile long lastActiveTime;
        private volatile String token;
        private volatile String attemptId;

        public ClientSessionEntry(String userId, String username, Socket socket, BufferedWriter writer, int serverId) {
            this.userId = userId;
            this.username = username;
            this.clientSocket = socket;
            this.clientWriter = writer;
            this.assignedServerId = serverId;
            this.loginTime = System.currentTimeMillis();
            this.lastActiveTime = loginTime;
            this.inExam = false;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getAttemptId() {
            return attemptId;
        }

        public void setAttemptId(String attemptId) {
            this.attemptId = attemptId;
        }

        public boolean isAlive() {
            return clientSocket != null && !clientSocket.isClosed() && clientSocket.isConnected();
        }

        public void sendPushMessage(Message message) {
            try {
                if (clientWriter != null && isAlive()) {
                    clientWriter.write(JsonUtil.toJson(message) + "\n");
                    clientWriter.flush();
                }
            } catch (IOException e) {
                log.warn("Failed to send push message to user {}: {}", username, e.getMessage());
            }
        }

        public void closeSocket() {
            try {
                if (clientSocket != null && !clientSocket.isClosed()) {
                    clientSocket.close();
                }
            } catch (IOException ignored) {
            }
        }

        public String getUserId() {
            return userId;
        }

        public String getUsername() {
            return username;
        }

        public int getAssignedServerId() {
            return assignedServerId;
        }

        public void setAssignedServerId(int assignedServerId) {
            this.assignedServerId = assignedServerId;
        }

        public boolean isInExam() {
            return inExam;
        }

        public void setInExam(boolean inExam) {
            this.inExam = inExam;
        }

        public long getLastActiveTime() {
            return lastActiveTime;
        }

        public void updateActiveTime() {
            this.lastActiveTime = System.currentTimeMillis();
        }

        public Socket getClientSocket() {
            return clientSocket;
        }

        public void updateSocket(Socket socket, BufferedWriter writer) {
            this.clientSocket = socket;
            this.clientWriter = writer;
            this.lastActiveTime = System.currentTimeMillis();
        }
    }

    // userId -> session entry
    private final Map<String, ClientSessionEntry> sessionsByUserId = new ConcurrentHashMap<>();
    // socket -> userId
    private final Map<Socket, String> socketToUserId = new ConcurrentHashMap<>();

    public synchronized void registerLogin(String userId, String username, Socket socket, BufferedWriter writer, int serverId) throws SessionException {
        ClientSessionEntry existing = sessionsByUserId.get(userId);

        if (existing != null && existing.isAlive()) {
            if (existing.isInExam()) {
                // Rule: If in exam, reject new login with 409 SESSION_IN_EXAM
                log.warn("User {} is currently in exam on active socket. Rejecting duplicate login with 409.", username);
                throw new SessionException(ErrorCode.SESSION_IN_EXAM, "Tài khoản đang trong ca thi ở nơi khác");
            } else {
                // Rule: Not in exam, send SESSION_REPLACED to old connection and close it
                log.info("User {} logged in from new location. Replacing previous session.", username);
                Message replaceMsg = Message.error(MessageType.SESSION_REPLACED, "SYS-" + System.currentTimeMillis(),
                        ErrorCode.SESSION_IN_EXAM, "Tài khoản của bạn đã được đăng nhập từ một thiết bị khác.");
                existing.sendPushMessage(replaceMsg);
                existing.closeSocket();
                socketToUserId.remove(existing.getClientSocket());
            }
        }

        ClientSessionEntry newEntry = new ClientSessionEntry(userId, username, socket, writer, serverId);
        sessionsByUserId.put(userId, newEntry);
        socketToUserId.put(socket, userId);
    }

    public synchronized boolean resumeSession(String userId, Socket socket, BufferedWriter writer) {
        ClientSessionEntry existing = sessionsByUserId.get(userId);
        if (existing != null) {
            Socket oldSocket = existing.getClientSocket();
            if (oldSocket != null && oldSocket != socket) {
                socketToUserId.remove(oldSocket);
                try {
                    oldSocket.close();
                } catch (IOException ignored) {}
            }
            existing.updateSocket(socket, writer);
            socketToUserId.put(socket, userId);
            log.info("Session resumed for user {}", existing.getUsername());
            return true;
        }
        return false;
    }

    public void setExamActive(String userId, boolean active) {
        ClientSessionEntry entry = sessionsByUserId.get(userId);
        if (entry != null) {
            entry.setInExam(active);
            log.debug("Set exam active={} for user {}", active, entry.getUsername());
        }
    }

    public ClientSessionEntry getSessionByUserId(String userId) {
        return sessionsByUserId.get(userId);
    }

    public ClientSessionEntry getSessionBySocket(Socket socket) {
        String userId = socketToUserId.get(socket);
        return userId != null ? sessionsByUserId.get(userId) : null;
    }

    public void unregisterSocket(Socket socket) {
        String userId = socketToUserId.remove(socket);
        if (userId != null) {
            ClientSessionEntry entry = sessionsByUserId.get(userId);
            if (entry != null && entry.getClientSocket() == socket) {
                // Keep session entry in memory if in exam so they can resume, otherwise clean
                if (!entry.isInExam()) {
                    sessionsByUserId.remove(userId);
                }
            }
        }
    }

    public java.util.List<ClientSessionEntry> getSessionsForServer(int serverId) {
        java.util.List<ClientSessionEntry> list = new java.util.ArrayList<>();
        for (ClientSessionEntry entry : sessionsByUserId.values()) {
            if (entry.getAssignedServerId() == serverId && entry.isAlive()) {
                list.add(entry);
            }
        }
        return list;
    }

    public ClientSessionEntry getSessionByAttemptId(String attemptId) {
        if (attemptId == null) return null;
        for (ClientSessionEntry entry : sessionsByUserId.values()) {
            if (attemptId.equals(entry.getAttemptId())) {
                return entry;
            }
        }
        return null;
    }

    public int getActiveSessionCount() {
        return (int) sessionsByUserId.values().stream().filter(ClientSessionEntry::isAlive).count();
    }

    public static class SessionException extends Exception {
        private final ErrorCode errorCode;

        public SessionException(ErrorCode errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public ErrorCode getErrorCode() {
            return errorCode;
        }
    }
}
