package vn.edu.ptit.exam.server.dao;

import vn.edu.ptit.exam.common.dto.LogEntryDto;
import vn.edu.ptit.exam.common.dto.LogQueryResponse;
import vn.edu.ptit.exam.server.db.DatabaseManager;
import vn.edu.ptit.exam.server.sync.SyncLogWriter;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Adapter delegating to SyncLogWriter for backwards compatibility and easy dependency injection.
 * Invariant 3: Business updates and sync_log entries MUST be committed in the same transaction.
 */
public class SyncLogDao {

    private final DatabaseManager dbManager;
    private final SyncLogWriter writer;

    public SyncLogDao(DatabaseManager dbManager, String serverId) {
        this.dbManager = dbManager;
        this.writer = new SyncLogWriter(dbManager, serverId);
    }

    public SyncLogDao(DatabaseManager dbManager, SyncLogWriter writer) {
        this.dbManager = dbManager;
        this.writer = writer;
    }

    public SyncLogDao(SyncLogWriter writer) {
        this.dbManager = null;
        this.writer = writer;
    }

    public SyncLogWriter getWriter() {
        return writer;
    }

    public String writeSyncLog(Connection conn, String tableName, String recordId,
                               String opType, int version, Object payload) throws SQLException {
        return writer.writeSyncLog(conn, tableName, recordId, opType, version, payload);
    }

    public long getCurrentSeq() {
        return writer.getCurrentSeq();
    }

    public LogQueryResponse queryLogs(String keyword, Long startTime, Long endTime, int page, int pageSize) throws SQLException {
        if (dbManager == null) {
            return new LogQueryResponse("SYNC", 0, page, pageSize, new ArrayList<>());
        }

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
            where.append("AND (LOWER(op_id) LIKE ? OR LOWER(op_type) LIKE ? OR LOWER(table_name) LIKE ? OR LOWER(record_id) LIKE ?) ");
            String pat = "%" + keyword.trim().toLowerCase() + "%";
            params.add(pat);
            params.add(pat);
            params.add(pat);
            params.add(pat);
        }

        String countSql = "SELECT COUNT(*) FROM sync_log " + where;
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

        String querySql = "SELECT op_id, origin, seq, table_name, record_id, op_type, version, status, created_at " +
                          "FROM sync_log " + where + "ORDER BY created_at DESC LIMIT ? OFFSET ?";
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
                    String details = String.format("table=%s, record=%s, version=%d",
                            rs.getString("table_name"), rs.getString("record_id"), rs.getInt("version"));
                    list.add(new LogEntryDto(
                            ts,
                            "SYNC",
                            rs.getString("op_id"),
                            rs.getString("origin"),
                            rs.getString("op_type"),
                            null,
                            rs.getString("status"),
                            details
                    ));
                }
            }
        }

        return new LogQueryResponse("SYNC", total, safePage, safePageSize, list);
    }
}
