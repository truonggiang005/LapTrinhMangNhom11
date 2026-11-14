package vn.edu.ptit.exam.server.handler;

import com.fasterxml.jackson.databind.JsonNode;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.common.config.Config;
import vn.edu.ptit.exam.common.dto.AuthToken;
import vn.edu.ptit.exam.common.dto.Message;
import vn.edu.ptit.exam.common.enums.ErrorCode;
import vn.edu.ptit.exam.common.enums.MessageType;
import vn.edu.ptit.exam.common.model.User;
import vn.edu.ptit.exam.common.util.HmacUtil;
import vn.edu.ptit.exam.common.util.JsonUtil;
import vn.edu.ptit.exam.server.dao.AuditLogDao;
import vn.edu.ptit.exam.server.dao.UserDao;
import vn.edu.ptit.exam.server.net.SocketContext;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles user authentication (LOGIN).
 * Enforces BCrypt verification, in-memory 5-failure lock for 5 minutes, and issues 4-hour HMAC tokens.
 */
public class LoginHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(LoginHandler.class);
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long LOCK_DURATION_MS = 5 * 60 * 1000L; // 5 minutes

    private final UserDao userDao;
    private final AuditLogDao auditLogDao;
    private final String serverId;
    private final String hmacSecretKey;
    private final long tokenExpiryMillis;

    // In-memory failed login tracking: username -> FailedLoginState
    private final ConcurrentHashMap<String, FailedLoginState> failedLogins = new ConcurrentHashMap<>();

    public LoginHandler(UserDao userDao, AuditLogDao auditLogDao, String serverId) {
        this.userDao = userDao;
        this.auditLogDao = auditLogDao;
        this.serverId = serverId;
        Config config = Config.getInstance();
        this.hmacSecretKey = config.getHmacSecretKey();
        this.tokenExpiryMillis = config.getTokenExpiryMillis();
    }

    @Override
    public Message handle(Message request, SocketContext ctx) throws Exception {
        if (request.getPayload() == null) {
            return Message.error(MessageType.LOGIN, request.getRequestId(), ErrorCode.BAD_REQUEST, "Thiếu thông tin đăng nhập");
        }

        JsonNode node = JsonUtil.readTree(JsonUtil.toJson(request.getPayload()));
        String username = node.has("username") ? node.get("username").asText().trim() : null;
        String password = node.has("password") ? node.get("password").asText() : null;

        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            return Message.error(MessageType.LOGIN, request.getRequestId(), ErrorCode.BAD_REQUEST, "Tên đăng nhập và mật khẩu không được để trống");
        }

        long now = System.currentTimeMillis();

        // 1. Check in-memory lock
        FailedLoginState state = failedLogins.computeIfAbsent(username.toLowerCase(), k -> new FailedLoginState());
        synchronized (state) {
            if (state.lockedUntil > now) {
                long remainingSec = (state.lockedUntil - now + 999) / 1000;
                auditLogDao.log("LOGIN_FAIL_LOCKED", null, "USER", username, serverId, ctx.getRemoteAddress(), "Tài khoản đang bị khóa tạm thời");
                return Message.error(MessageType.LOGIN, request.getRequestId(), ErrorCode.UNAUTHENTICATED,
                        "Tài khoản bị tạm khóa do nhập sai quá 5 lần. Vui lòng thử lại sau " + remainingSec + " giây");
            }
        }

        // 2. Fetch user from database
        User user = userDao.findByUsername(username);

        // 3. Verify user and password
        boolean passwordMatches = false;
        if (user != null && user.getPasswordHash() != null) {
            try {
                passwordMatches = BCrypt.checkpw(password, user.getPasswordHash());
            } catch (Exception e) {
                log.warn("BCrypt check failed for user {}: {}", username, e.getMessage());
            }
        }

        if (!passwordMatches) {
            int currentFailures;
            long lockedUntil = 0;
            synchronized (state) {
                state.failedCount++;
                currentFailures = state.failedCount;
                if (state.failedCount >= MAX_FAILED_ATTEMPTS) {
                    state.lockedUntil = now + LOCK_DURATION_MS;
                    state.failedCount = 0; // reset counter after locking
                    lockedUntil = state.lockedUntil;
                }
            }

            if (user != null) {
                userDao.updateFailedLogin(user.getId(), currentFailures, lockedUntil > 0 ? new Timestamp(lockedUntil) : null);
            }

            auditLogDao.log("LOGIN_FAIL", user != null ? user.getId() : null, "USER", username, serverId, ctx.getRemoteAddress(),
                    "Mật khẩu không đúng. Số lần sai: " + currentFailures);

            if (lockedUntil > 0) {
                return Message.error(MessageType.LOGIN, request.getRequestId(), ErrorCode.UNAUTHENTICATED,
                        "Tài khoản bị tạm khóa 5 phút do nhập sai quá 5 lần liên tiếp");
            } else {
                int remaining = MAX_FAILED_ATTEMPTS - currentFailures;
                return Message.error(MessageType.LOGIN, request.getRequestId(), ErrorCode.UNAUTHENTICATED,
                        "Sai tên đăng nhập hoặc mật khẩu (còn " + remaining + " lần thử trước khi khóa)");
            }
        }

        // 4. Check if user is locked in database
        if (user.isLocked()) {
            auditLogDao.log("LOGIN_FAIL_LOCKED_DB", user.getId(), "USER", username, serverId, ctx.getRemoteAddress(), "Tài khoản bị khóa trong CSDL");
            return Message.error(MessageType.LOGIN, request.getRequestId(), ErrorCode.FORBIDDEN, "Tài khoản của bạn đã bị khóa. Vui lòng liên hệ quản trị viên");
        }

        // 5. Login successful: reset failed counters
        synchronized (state) {
            state.failedCount = 0;
            state.lockedUntil = 0;
        }
        userDao.resetFailedLogin(user.getId());

        // 6. Generate 4-hour AuthToken
        long expiresAt = now + tokenExpiryMillis;
        AuthToken authToken = new AuthToken(user.getId(), user.getUsername(), user.getFullName(), user.getRole(), now, expiresAt);
        String tokenString = HmacUtil.createToken(authToken, hmacSecretKey);

        ctx.setAuthenticatedToken(authToken);

        // 7. Audit log
        auditLogDao.log("LOGIN_OK", user.getId(), "USER", user.getId(), serverId, ctx.getRemoteAddress(), "Đăng nhập thành công");

        // 8. Build response payload
        Map<String, Object> responsePayload = new HashMap<>();
        responsePayload.put("token", tokenString);
        responsePayload.put("userId", user.getId());
        responsePayload.put("username", user.getUsername());
        responsePayload.put("fullName", user.getFullName());
        responsePayload.put("role", user.getRole().name());
        responsePayload.put("serverTime", now);

        Message response = Message.ok(MessageType.LOGIN, request.getRequestId(), responsePayload);
        response.setToken(tokenString);
        return response;
    }

    private static class FailedLoginState {
        int failedCount = 0;
        long lockedUntil = 0;
    }
}
