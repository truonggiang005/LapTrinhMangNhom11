package vn.edu.ptit.exam.server.handler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;
import vn.edu.ptit.exam.common.config.Config;
import vn.edu.ptit.exam.common.dto.AuthToken;
import vn.edu.ptit.exam.common.dto.BatchUserResultDto;
import vn.edu.ptit.exam.common.dto.Message;
import vn.edu.ptit.exam.common.dto.UserDto;
import vn.edu.ptit.exam.common.enums.ErrorCode;
import vn.edu.ptit.exam.common.enums.MessageType;
import vn.edu.ptit.exam.common.enums.UserRole;
import vn.edu.ptit.exam.common.model.User;
import vn.edu.ptit.exam.common.util.HmacUtil;
import vn.edu.ptit.exam.common.util.JsonUtil;
import vn.edu.ptit.exam.server.dao.UserDao;
import vn.edu.ptit.exam.server.exception.ExamServerException;
import vn.edu.ptit.exam.server.sync.SyncLogWriter;

import java.sql.SQLException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class UserBatchImportTest {

    private UserDao inMemoryUserDao;
    private UserAdminHandler userAdminHandler;
    private final String hmacKey = Config.getInstance().getHmacSecretKey();

    private String adminToken;
    private String lecturerToken;

    private final Map<String, User> userTable = new HashMap<>();

    @BeforeEach
    void setUp() {
        userTable.clear();

        long now = System.currentTimeMillis();
        AuthToken admin = new AuthToken("u-adm-1", "admin", "System Admin", UserRole.ADMIN, now, now + 14400000L);
        adminToken = HmacUtil.createToken(admin, hmacKey);

        AuthToken lect = new AuthToken("u-lect-1", "gv_lan", "Nguyen Thi Lan", UserRole.LECTURER, now, now + 14400000L);
        lecturerToken = HmacUtil.createToken(lect, hmacKey);

        // Pre-populate with an existing user
        User existing = new User("u-sv-dup", "sv_dup", "hashed", "Sinh Vien Trung", "sv_dup@ptit.edu.vn",
                UserRole.STUDENT, "D21CN1", "ACTIVE", 0, null, 1);
        userTable.put("sv_dup", existing);

        inMemoryUserDao = new UserDao(null) {
            @Override
            public User findByUsername(String username) {
                return userTable.get(username);
            }

            @Override
            public User findById(String id) {
                for (User u : userTable.values()) {
                    if (id.equals(u.getId())) return u;
                }
                return null;
            }

            @Override
            public UserDto createUser(UserDto dto, SyncLogWriter syncLogWriter) throws ExamServerException {
                if (dto.getUsername() == null || dto.getUsername().trim().isEmpty()) {
                    throw new ExamServerException(ErrorCode.BAD_REQUEST, "Tên đăng nhập không được để trống");
                }
                if (dto.getPassword() == null || dto.getPassword().trim().isEmpty()) {
                    throw new ExamServerException(ErrorCode.BAD_REQUEST, "Mật khẩu không được để trống");
                }
                if (userTable.containsKey(dto.getUsername().trim())) {
                    throw new ExamServerException(ErrorCode.BAD_REQUEST, "Tên đăng nhập đã tồn tại trong hệ thống");
                }

                String id = dto.getId() != null ? dto.getId() : UUID.randomUUID().toString();
                String hash = BCrypt.hashpw(dto.getPassword(), BCrypt.gensalt());
                User u = new User(id, dto.getUsername(), hash, dto.getFullName(), dto.getEmail(),
                        dto.getRole() != null ? dto.getRole() : UserRole.STUDENT,
                        dto.getClassName(), dto.getStatus() != null ? dto.getStatus() : "ACTIVE",
                        0, null, 1);
                userTable.put(dto.getUsername(), u);

                dto.setId(id);
                dto.setVersion(1);
                dto.setPassword(null);
                return dto;
            }

            @Override
            public void resetPassword(String userId, String newPassword, SyncLogWriter syncLogWriter)
                    throws ExamServerException {
                User u = findById(userId);
                if (u == null) throw new ExamServerException(ErrorCode.NOT_FOUND, "Không tìm thấy người dùng");
                u.setPasswordHash(BCrypt.hashpw(newPassword, BCrypt.gensalt()));
                u.setVersion(u.getVersion() + 1);
            }

            @Override
            public void setUserLocked(String userId, boolean locked, SyncLogWriter syncLogWriter)
                    throws ExamServerException {
                User u = findById(userId);
                if (u == null) throw new ExamServerException(ErrorCode.NOT_FOUND, "Không tìm thấy người dùng");
                u.setStatus(locked ? "LOCKED" : "ACTIVE");
                u.setVersion(u.getVersion() + 1);
            }
        };

        userAdminHandler = new UserAdminHandler(inMemoryUserDao, null);
    }

    @Test
    @DisplayName("Admin batch import with errored rows: imports valid ones, records error details with row index")
    void testUserBatchImport() throws Exception {
        List<UserDto> batch = new ArrayList<>();

        // Row 1: Valid student
        UserDto u1 = new UserDto();
        u1.setUsername("sv_01");
        u1.setPassword("Pass123@");
        u1.setFullName("Nguyen Van Mot");
        u1.setRole(UserRole.STUDENT);
        batch.add(u1);

        // Row 2: Invalid - empty password
        UserDto u2 = new UserDto();
        u2.setUsername("sv_02");
        u2.setPassword("");
        u2.setFullName("Nguyen Van Hai");
        batch.add(u2);

        // Row 3: Valid lecturer
        UserDto u3 = new UserDto();
        u3.setUsername("gv_01");
        u3.setPassword("LectPass123@");
        u3.setFullName("Tran Thi Ba");
        u3.setRole(UserRole.LECTURER);
        batch.add(u3);

        // Row 4: Invalid - duplicate username (already exists in DB)
        UserDto u4 = new UserDto();
        u4.setUsername("sv_dup");
        u4.setPassword("Pass123@");
        u4.setFullName("Trung Lap");
        batch.add(u4);

        // Row 5: Valid student
        UserDto u5 = new UserDto();
        u5.setUsername("sv_03");
        u5.setPassword("Pass123@");
        u5.setFullName("Le Van Nam");
        u5.setRole(UserRole.STUDENT);
        batch.add(u5);

        Message req = Message.request(MessageType.USER_BATCH, "req-batch-1");
        req.setToken(adminToken);
        req.setPayload(batch);

        Message resp = userAdminHandler.handle(req, null);
        assertEquals("OK", resp.getStatus());

        BatchUserResultDto result = JsonUtil.convertValue(resp.getPayload(), BatchUserResultDto.class);
        assertEquals(5, result.getTotalRows());
        assertEquals(3, result.getImportedCount());
        assertEquals(2, result.getFailedCount());
        assertEquals(2, result.getErrors().size());

        // Verify row indices in error list
        assertEquals(2, result.getErrors().get(0).getRowIndex());
        assertEquals("sv_02", result.getErrors().get(0).getUsername());
        assertTrue(result.getErrors().get(0).getErrorMessage().contains("Mật khẩu không được để trống"));

        assertEquals(4, result.getErrors().get(1).getRowIndex());
        assertEquals("sv_dup", result.getErrors().get(1).getUsername());
        assertTrue(result.getErrors().get(1).getErrorMessage().contains("đã tồn tại"));

        // Verify valid users exist in DB
        assertNotNull(userTable.get("sv_01"));
        assertNotNull(userTable.get("gv_01"));
        assertNotNull(userTable.get("sv_03"));
    }

    @Test
    @DisplayName("Admin reset password & lock/unlock account")
    void testResetPasswordAndLockAccount() throws Exception {
        User existing = userTable.get("sv_dup");

        // Reset password
        Message resetReq = Message.request(MessageType.USER_RESET_PASSWORD, "req-reset");
        resetReq.setToken(adminToken);
        Map<String, Object> resetPayload = new HashMap<>();
        resetPayload.put("userId", existing.getId());
        resetPayload.put("newPassword", "NewSecurePass!456");
        resetReq.setPayload(resetPayload);

        Message resetResp = userAdminHandler.handle(resetReq, null);
        assertEquals("OK", resetResp.getStatus());
        assertTrue(BCrypt.checkpw("NewSecurePass!456", existing.getPasswordHash()));
        assertEquals(2, existing.getVersion());

        // Lock user
        Message lockReq = Message.request(MessageType.USER_LOCK, "req-lock");
        lockReq.setToken(adminToken);
        Map<String, Object> lockPayload = new HashMap<>();
        lockPayload.put("userId", existing.getId());
        lockPayload.put("locked", true);
        lockReq.setPayload(lockPayload);

        Message lockResp = userAdminHandler.handle(lockReq, null);
        assertEquals("OK", lockResp.getStatus());
        assertEquals("LOCKED", existing.getStatus());
        assertTrue(existing.isLocked());

        // Unlock user
        lockPayload.put("locked", false);
        lockReq.setPayload(lockPayload);
        Message unlockResp = userAdminHandler.handle(lockReq, null);
        assertEquals("OK", unlockResp.getStatus());
        assertEquals("ACTIVE", existing.getStatus());
        assertFalse(existing.isLocked());
    }

    @Test
    @DisplayName("Non-admin user (e.g. Lecturer) receives 403 FORBIDDEN on user management")
    void testNonAdminForbidden() throws Exception {
        Message req = Message.request(MessageType.USER_BATCH, "req-lect-forbidden");
        req.setToken(lecturerToken);
        req.setPayload(Collections.emptyList());

        Message resp = userAdminHandler.handle(req, null);
        assertEquals("ERROR", resp.getStatus());
        assertEquals(ErrorCode.FORBIDDEN.getCode(), resp.getCode());
    }
}
