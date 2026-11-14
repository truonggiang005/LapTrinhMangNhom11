package vn.edu.ptit.exam.server.dao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.common.dto.LogEntryDto;
import vn.edu.ptit.exam.common.dto.LogQueryResponse;
import vn.edu.ptit.exam.server.db.DatabaseManager;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class AuditLogDao {

    private static final Logger log = LoggerFactory.getLogger(AuditLogDao.class);
    private final DatabaseManager dbManager;

    public AuditLogDao(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    public void log(String action, String userId, String entityType, String entityId,
                    String serverId, String ipAddress, String details) {
        String sql = "INSERT INTO audit_log (id, user_id, action, entity_type, entity_id, server_id, ip_address, details, created_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW())";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, userId);
            ps.setString(3, action);
            ps.setString(4, entityType);
            ps.setString(5, entityId);
            ps.setString(6, serverId);
            ps.setString(7, ipAddress);
            ps.setString(8, details);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to write audit log [action={}, user={}]: {}", action, userId, e.getMessage(), e);
        }
    }

    public LogQueryResponse queryLogs(String keyword, Long startTime, Long endTime, int page, int pageSize) throws SQLException {
        StringBuilder where = new StringBuilder("WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        if (startTime != null && startTime > 0) {
            where.append("AND created_at >= ? ");
            params.add(new Timestamp(startTime));
        }
        if (endTime != null && endTime > 0) {
            where.append("AND created_at <= ? ");
            params.add(new Timestamp(endTime));
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            where.append("AND (LOWER(action) LIKE ? OR LOWER(user_id) LIKE ? OR LOWER(details) LIKE ?) ");
            String pat = "%" + keyword.trim().toLowerCase() + "%";
            params.add(pat);
            params.add(pat);
            params.add(pat);
        }

        String countSql = "SELECT COUNT(*) FROM audit_log " + where;
        int total = 0;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(countSql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    total = rs.getInt(1);
                }
            }
        }

        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, pageSize);
        int offset = (safePage - 1) * safePageSize;

        String querySql = "SELECT id, user_id, action, entity_type, entity_id, server_id, details, created_at " +
                          "FROM audit_log " + where + "ORDER BY created_at DESC LIMIT ? OFFSET ?";
        List<LogEntryDto> list = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(querySql)) {
            int idx = 1;
            for (Object param : params) {
                ps.setObject(idx++, param);
            }
            ps.setInt(idx++, safePageSize);
            ps.setInt(idx, offset);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long ts = rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").getTime() : 0L;
                    list.add(new LogEntryDto(
                            ts,
                            "AUDIT",
                            rs.getString("id"),
                            rs.getString("user_id"),
                            rs.getString("action"),
                            rs.getString("server_id"),
                            "SUCCESS",
                            rs.getString("details")
                    ));
                }
            }
        }

        return new LogQueryResponse("AUDIT", total, safePage, safePageSize, list);
    }
}
