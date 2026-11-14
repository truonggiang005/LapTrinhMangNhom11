package vn.edu.ptit.exam.server.dao;

import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.common.dto.BatchRowError;
import vn.edu.ptit.exam.common.dto.BatchUserResultDto;
import vn.edu.ptit.exam.common.dto.UserDto;
import vn.edu.ptit.exam.common.dto.UserListResponse;
import vn.edu.ptit.exam.common.enums.ErrorCode;
import vn.edu.ptit.exam.common.enums.UserRole;
import vn.edu.ptit.exam.common.model.User;
import vn.edu.ptit.exam.server.db.DatabaseManager;
import vn.edu.ptit.exam.server.exception.ExamServerException;
import vn.edu.ptit.exam.server.sync.SyncLogWriter;

import java.sql.*;
import java.util.*;

public class UserDao {

    private static final Logger log = LoggerFactory.getLogger(UserDao.class);
    private final DatabaseManager dbManager;

    public UserDao(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    public User findByUsername(String username) throws SQLException {
        String sql = "SELECT id, username, password_hash, full_name, email, role, class_name, status, " +
                     "failed_login_count, locked_until, version FROM users WHERE username = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRow(rs);
                }
            }
        }
        return null;
    }

    public User findById(String id) throws SQLException {
        String sql = "SELECT id, username, password_hash, full_name, email, role, class_name, status, " +
                     "failed_login_count, locked_until, version FROM users WHERE id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRow(rs);
                }
            }
        }
        return null;
    }

    public void updateFailedLogin(String id, int failedCount, Timestamp lockedUntil) throws SQLException {
        String sql = "UPDATE users SET failed_login_count = ?, locked_until = ?, updated_at = NOW() WHERE id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, failedCount);
            ps.setTimestamp(2, lockedUntil);
            ps.setString(3, id);
            ps.executeUpdate();
        }
    }

    public void resetFailedLogin(String id) throws SQLException {
        String sql = "UPDATE users SET failed_login_count = 0, locked_until = NULL, updated_at = NOW() WHERE id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }

    // =========================================================================
    // USER MANAGEMENT (CREATE, UPDATE, RESET PASSWORD, LOCK, BATCH IMPORT)
    // =========================================================================

    public UserDto createUser(UserDto dto, SyncLogWriter syncLogWriter) throws SQLException, ExamServerException {
        if (dto.getUsername() == null || dto.getUsername().trim().isEmpty()) {
            throw new ExamServerException(ErrorCode.BAD_REQUEST, "Tên đăng nhập không được để trống");
        }
        if (dto.getPassword() == null || dto.getPassword().trim().isEmpty()) {
            throw new ExamServerException(ErrorCode.BAD_REQUEST, "Mật khẩu không được để trống");
        }

        String username = dto.getUsername().trim();
        if (findByUsername(username) != null) {
            throw new ExamServerException(ErrorCode.BAD_REQUEST, "Tên đăng nhập đã tồn tại: " + username);
        }

        String id = dto.getId() != null ? dto.getId() : UUID.randomUUID().toString();
        String passwordHash = BCrypt.hashpw(dto.getPassword(), BCrypt.gensalt());
        String roleStr = dto.getRole() != null ? dto.getRole().name() : "STUDENT";
        String statusStr = dto.getStatus() != null ? dto.getStatus() : "ACTIVE";

        dto.setId(id);
        dto.setVersion(1);
        dto.setStatus(statusStr);

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String insertSql = "INSERT INTO users (id, username, password_hash, full_name, email, role, class_name, status, version, created_at, updated_at) " +
                                  "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, NOW(), NOW())";
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    ps.setString(1, id);
                    ps.setString(2, username);
                    ps.setString(3, passwordHash);
                    ps.setString(4, dto.getFullName() != null ? dto.getFullName().trim() : username);
                    ps.setString(5, dto.getEmail() != null ? dto.getEmail().trim() : null);
                    ps.setString(6, roleStr);
                    ps.setString(7, dto.getClassName() != null ? dto.getClassName().trim() : null);
                    ps.setString(8, statusStr);
                    ps.executeUpdate();
                }

                // Invariant 3: Transactional sync_log write
                if (syncLogWriter != null) {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("id", id);
                    payload.put("username", username);
                    payload.put("passwordHash", passwordHash);
                    payload.put("fullName", dto.getFullName());
                    payload.put("email", dto.getEmail());
                    payload.put("role", roleStr);
                    payload.put("className", dto.getClassName());
                    payload.put("status", statusStr);
                    payload.put("version", 1);
                    syncLogWriter.writeSyncLog(conn, "users", id, "USER_CREATE", 1, payload);
                }

                conn.commit();
                log.info("Created user [id={}, username={}, role={}]", id, username, roleStr);
                dto.setPassword(null); // Never return password
                return dto;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    public UserDto updateUser(UserDto dto, SyncLogWriter syncLogWriter) throws SQLException, ExamServerException {
        if (dto.getId() == null) {
            throw new ExamServerException(ErrorCode.BAD_REQUEST, "Thiếu mã người dùng cần sửa");
        }
        User existing = findById(dto.getId());
        if (existing == null) {
            throw new ExamServerException(ErrorCode.NOT_FOUND, "Không tìm thấy người dùng với mã " + dto.getId());
        }

        int newVersion = existing.getVersion() + 1;
        String roleStr = dto.getRole() != null ? dto.getRole().name() : existing.getRole().name();
        String statusStr = dto.getStatus() != null ? dto.getStatus() : existing.getStatus();
        String fullName = dto.getFullName() != null ? dto.getFullName() : existing.getFullName();
        String email = dto.getEmail() != null ? dto.getEmail() : existing.getEmail();
        String className = dto.getClassName() != null ? dto.getClassName() : existing.getClassName();

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String updateSql = "UPDATE users SET full_name = ?, email = ?, role = ?, class_name = ?, status = ?, version = ?, updated_at = NOW() " +
                                  "WHERE id = ?";
                try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                    ps.setString(1, fullName);
                    ps.setString(2, email);
                    ps.setString(3, roleStr);
                    ps.setString(4, className);
                    ps.setString(5, statusStr);
                    ps.setInt(6, newVersion);
                    ps.setString(7, dto.getId());
                    ps.executeUpdate();
                }

                if (syncLogWriter != null) {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("id", dto.getId());
                    payload.put("username", existing.getUsername());
                    payload.put("fullName", fullName);
                    payload.put("email", email);
                    payload.put("role", roleStr);
                    payload.put("className", className);
                    payload.put("status", statusStr);
                    payload.put("version", newVersion);
                    syncLogWriter.writeSyncLog(conn, "users", dto.getId(), "USER_UPDATE", newVersion, payload);
                }

                conn.commit();
                dto.setUsername(existing.getUsername());
                dto.setFullName(fullName);
                dto.setEmail(email);
                dto.setRole(UserRole.valueOf(roleStr));
                dto.setClassName(className);
                dto.setStatus(statusStr);
                dto.setVersion(newVersion);
                return dto;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    public void resetPassword(String userId, String newPassword, SyncLogWriter syncLogWriter)
            throws SQLException, ExamServerException {
        if (newPassword == null || newPassword.trim().isEmpty()) {
            throw new ExamServerException(ErrorCode.BAD_REQUEST, "Mật khẩu mới không được để trống");
        }
        User existing = findById(userId);
        if (existing == null) {
            throw new ExamServerException(ErrorCode.NOT_FOUND, "Không tìm thấy người dùng với mã " + userId);
        }

        int newVersion = existing.getVersion() + 1;
        String passwordHash = BCrypt.hashpw(newPassword.trim(), BCrypt.gensalt());

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String sql = "UPDATE users SET password_hash = ?, failed_login_count = 0, locked_until = NULL, version = ?, updated_at = NOW() WHERE id = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, passwordHash);
                    ps.setInt(2, newVersion);
                    ps.setString(3, userId);
                    ps.executeUpdate();
                }

                if (syncLogWriter != null) {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("id", userId);
                    payload.put("username", existing.getUsername());
                    payload.put("passwordHash", passwordHash);
                    payload.put("fullName", existing.getFullName());
                    payload.put("role", existing.getRole().name());
                    payload.put("status", existing.getStatus());
                    payload.put("version", newVersion);
                    syncLogWriter.writeSyncLog(conn, "users", userId, "USER_UPDATE", newVersion, payload);
                }

                conn.commit();
                log.info("Reset password for user {}", existing.getUsername());
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    public void setUserLocked(String userId, boolean locked, SyncLogWriter syncLogWriter)
            throws SQLException, ExamServerException {
        User existing = findById(userId);
        if (existing == null) {
            throw new ExamServerException(ErrorCode.NOT_FOUND, "Không tìm thấy người dùng với mã " + userId);
        }

        int newVersion = existing.getVersion() + 1;
        String statusStr = locked ? "LOCKED" : "ACTIVE";

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String sql = "UPDATE users SET status = ?, failed_login_count = 0, locked_until = NULL, version = ?, updated_at = NOW() WHERE id = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, statusStr);
                    ps.setInt(2, newVersion);
                    ps.setString(3, userId);
                    ps.executeUpdate();
                }

                if (syncLogWriter != null) {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("id", userId);
                    payload.put("username", existing.getUsername());
                    payload.put("status", statusStr);
                    payload.put("version", newVersion);
                    syncLogWriter.writeSyncLog(conn, "users", userId, "USER_UPDATE", newVersion, payload);
                }

                conn.commit();
                log.info("Set user {} lock status to {}", existing.getUsername(), statusStr);
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /**
     * Batch imports users row by row.
     * Validates each row; commits valid rows and records errors with specific row index and reasons.
     */
    public BatchUserResultDto importUsersBatch(List<UserDto> users, SyncLogWriter syncLogWriter) {
        if (users == null || users.isEmpty()) {
            return new BatchUserResultDto(0, 0, 0, Collections.emptyList());
        }

        int totalRows = users.size();
        int importedCount = 0;
        int failedCount = 0;
        List<BatchRowError> errors = new ArrayList<>();

        for (int i = 0; i < users.size(); i++) {
            int rowIndex = i + 1;
            UserDto u = users.get(i);
            String username = u.getUsername() != null ? u.getUsername().trim() : null;

            if (username == null || username.isEmpty()) {
                errors.add(new BatchRowError(rowIndex, "ROW_" + rowIndex, "Tên đăng nhập không được để trống"));
                failedCount++;
                continue;
            }

            if (u.getPassword() == null || u.getPassword().trim().isEmpty()) {
                errors.add(new BatchRowError(rowIndex, username, "Mật khẩu không được để trống"));
                failedCount++;
                continue;
            }

            try {
                if (findByUsername(username) != null) {
                    errors.add(new BatchRowError(rowIndex, username, "Tên đăng nhập đã tồn tại trong hệ thống"));
                    failedCount++;
                    continue;
                }

                createUser(u, syncLogWriter);
                importedCount++;
            } catch (Exception e) {
                log.warn("Batch import row {} failed: {}", rowIndex, e.getMessage());
                errors.add(new BatchRowError(rowIndex, username, "Lỗi tạo tài khoản: " + e.getMessage()));
                failedCount++;
            }
        }

        return new BatchUserResultDto(totalRows, importedCount, failedCount, errors);
    }

    public UserListResponse listUsers(String keyword, String role, int page, int pageSize) throws SQLException {
        if (page < 1) page = 1;
        if (pageSize < 1) pageSize = 20;
        int offset = (page - 1) * pageSize;

        StringBuilder whereClause = new StringBuilder(" WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        if (keyword != null && !keyword.trim().isEmpty()) {
            whereClause.append(" AND (username LIKE ? OR full_name LIKE ? OR email LIKE ?) ");
            String pattern = "%" + keyword.trim() + "%";
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
        }

        if (role != null && !role.trim().isEmpty() && !"ALL".equalsIgnoreCase(role.trim())) {
            whereClause.append(" AND role = ? ");
            params.add(role.trim().toUpperCase());
        }

        String countSql = "SELECT COUNT(*) FROM users " + whereClause;
        String querySql = "SELECT id, username, password_hash, full_name, email, role, class_name, status, " +
                "failed_login_count, locked_until, version FROM users " + whereClause + " ORDER BY created_at DESC LIMIT ? OFFSET ?";

        int total = 0;
        List<UserDto> users = new ArrayList<>();

        try (Connection conn = dbManager.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(countSql)) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        total = rs.getInt(1);
                    }
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(querySql)) {
                int paramIdx = 1;
                for (Object param : params) {
                    ps.setObject(paramIdx++, param);
                }
                ps.setInt(paramIdx++, pageSize);
                ps.setInt(paramIdx, offset);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        User u = mapRow(rs);
                        UserDto dto = new UserDto(u.getId(), u.getUsername(), u.getFullName(),
                                u.getEmail(), u.getRole(), u.getClassName(), u.getStatus(), u.getVersion());
                        users.add(dto);
                    }
                }
            }
        }

        return new UserListResponse(users, total, page, pageSize);
    }

    private User mapRow(ResultSet rs) throws SQLException {
        User user = new User();
        user.setId(rs.getString("id"));
        user.setUsername(rs.getString("username"));
        user.setPasswordHash(rs.getString("password_hash"));
        user.setFullName(rs.getString("full_name"));
        user.setEmail(rs.getString("email"));
        user.setRole(UserRole.valueOf(rs.getString("role")));
        user.setClassName(rs.getString("class_name"));
        user.setStatus(rs.getString("status"));
        user.setFailedLoginCount(rs.getInt("failed_login_count"));
        user.setLockedUntil(rs.getTimestamp("locked_until"));
        user.setVersion(rs.getInt("version"));
        return user;
    }
}
