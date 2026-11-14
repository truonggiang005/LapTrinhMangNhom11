package vn.edu.ptit.exam.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;
import vn.edu.ptit.exam.common.config.Config;
import vn.edu.ptit.exam.common.dto.AuthToken;
import vn.edu.ptit.exam.common.dto.Message;
import vn.edu.ptit.exam.common.enums.ErrorCode;
import vn.edu.ptit.exam.common.enums.MessageType;
import vn.edu.ptit.exam.common.enums.UserRole;
import vn.edu.ptit.exam.common.model.User;
import vn.edu.ptit.exam.common.util.HmacUtil;
import vn.edu.ptit.exam.server.dao.AuditLogDao;
import vn.edu.ptit.exam.server.dao.UserDao;
import vn.edu.ptit.exam.server.handler.LoginHandler;
import vn.edu.ptit.exam.server.net.SocketContext;

import java.net.Socket;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LoginHandlerTest {

    private UserDao mockUserDao;
    private AuditLogDao mockAuditLogDao;
    private LoginHandler loginHandler;
    private SocketContext dummyContext;

    // Test in-memory user repository
    private final Map<String, User> userDb = new HashMap<>();

    @BeforeEach
    void setUp() {
        userDb.clear();
        String hashedPw = BCrypt.hashpw("123456", BCrypt.gensalt());
        User student = new User("u-sv-001", "sv_01", hashedPw, "Nguyen Van A", "sv01@ptit.edu.vn",
                UserRole.STUDENT, "D21CQCN01-B", "ACTIVE", 0, null, 1);
        userDb.put("sv_01", student);

        mockUserDao = new UserDao(null) {
            @Override
            public User findByUsername(String username) throws SQLException {
                return userDb.get(username.toLowerCase());
            }

            @Override
            public void updateFailedLogin(String id, int failedCount, Timestamp lockedUntil) {
                User u = userDb.get("sv_01");
                if (u != null) {
                    u.setFailedLoginCount(failedCount);
                    u.setLockedUntil(lockedUntil);
                }
            }

            @Override
            public void resetFailedLogin(String id) {
                User u = userDb.get("sv_01");
                if (u != null) {
                    u.setFailedLoginCount(0);
                    u.setLockedUntil(null);
                }
            }
        };

        mockAuditLogDao = new AuditLogDao(null) {
            @Override
            public void log(String action, String userId, String entityType, String entityId, String serverId, String ipAddress, String details) {
                // In-memory no-op for testing
            }
        };

        loginHandler = new LoginHandler(mockUserDao, mockAuditLogDao, "SERVER_1");
        dummyContext = new SocketContext("test-conn-1", new Socket());
    }

    @Test
    @DisplayName("Should successfully authenticate valid user and issue HMAC token")
    void testSuccessfulLogin() throws Exception {
        Message req = Message.request(MessageType.LOGIN, "req-login-1");
        req.setPayload(Map.of("username", "sv_01", "password", "123456"));

        Message response = loginHandler.handle(req, dummyContext);

        assertNotNull(response);
        assertEquals("OK", response.getStatus());
        assertEquals(200, response.getCode());
        assertNotNull(response.getToken());

        // Verify issued token
        AuthToken token = HmacUtil.parseAndVerifyToken(response.getToken(), Config.getInstance().getHmacSecretKey());
        assertNotNull(token);
        assertEquals("u-sv-001", token.getUserId());
        assertEquals("sv_01", token.getUsername());
        assertEquals(UserRole.STUDENT, token.getRole());
        assertFalse(token.isExpired());
    }

    @Test
    @DisplayName("Should return 401 when password is wrong")
    void testWrongPassword() throws Exception {
        Message req = Message.request(MessageType.LOGIN, "req-login-2");
        req.setPayload(Map.of("username", "sv_01", "password", "wrong_password"));

        Message response = loginHandler.handle(req, dummyContext);

        assertNotNull(response);
        assertEquals("ERROR", response.getStatus());
        assertEquals(401, response.getCode());
        assertTrue(response.getMessage().contains("Sai tên đăng nhập hoặc mật khẩu"));
    }

    @Test
    @DisplayName("Should lock account for 5 minutes after 5 consecutive failed logins")
    void testLockAccountAfterFiveFailures() throws Exception {
        // Attempt 1 to 5 with wrong password
        for (int i = 1; i <= 5; i++) {
            Message req = Message.request(MessageType.LOGIN, "req-fail-" + i);
            req.setPayload(Map.of("username", "sv_01", "password", "wrong_pw"));
            Message resp = loginHandler.handle(req, dummyContext);
            assertEquals("ERROR", resp.getStatus());
            assertEquals(401, resp.getCode());
        }

        // Attempt 6 (even with correct password) should be blocked by in-memory lock
        Message reqLocked = Message.request(MessageType.LOGIN, "req-after-lock");
        reqLocked.setPayload(Map.of("username", "sv_01", "password", "123456"));
        Message respLocked = loginHandler.handle(reqLocked, dummyContext);

        assertEquals("ERROR", respLocked.getStatus());
        assertEquals(401, respLocked.getCode());
        assertTrue(respLocked.getMessage().contains("Tài khoản bị tạm khóa"));
    }

    @Test
    @DisplayName("Should reject empty or missing username/password with BAD_REQUEST 400")
    void testEmptyCredentials() throws Exception {
        Message req = Message.request(MessageType.LOGIN, "req-empty");
        req.setPayload(Map.of("username", "", "password", ""));

        Message resp = loginHandler.handle(req, dummyContext);

        assertEquals("ERROR", resp.getStatus());
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), resp.getCode());
    }
}
