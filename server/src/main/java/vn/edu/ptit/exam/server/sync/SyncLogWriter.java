package vn.edu.ptit.exam.server.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.common.util.JsonUtil;
import vn.edu.ptit.exam.server.db.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Transactional sync_log writer.
 * Assigns monotonically increasing sequence numbers per origin server: opId = origin-seq.
 * Bundles multi-table operations into atomic EXAM_PACKAGE.
 * Invariant 3: Must be written in the same transaction as the business operation.
 */
public class SyncLogWriter {

    private static final Logger log = LoggerFactory.getLogger(SyncLogWriter.class);

    private final DatabaseManager dbManager;
    private final String serverId;
    private final AtomicLong seqCounter = new AtomicLong(0);
    private volatile boolean initialized = false;

    // Optional listener to notify SyncManager immediately for priority operations (ATTEMPT_SUBMIT, ATTEMPT_CREATE)
    private volatile SyncPriorityListener priorityListener;

    public interface SyncPriorityListener {
        void onPriorityOperationLogged(String opType, String opId, long seq);
    }

    public SyncLogWriter(DatabaseManager dbManager, String serverId) {
        this.dbManager = dbManager;
        this.serverId = serverId;
    }

    public void setPriorityListener(SyncPriorityListener priorityListener) {
        this.priorityListener = priorityListener;
    }

    public synchronized void ensureInitialized() {
        if (initialized) return;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(MAX(seq), 0) FROM sync_log WHERE origin = ?")) {
            ps.setString(1, serverId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long maxSeq = rs.getLong(1);
                    seqCounter.set(maxSeq);
                    log.info("Initialized SyncLogWriter sequence for {} at seq={}", serverId, maxSeq);
                }
            }
            initialized = true;
        } catch (SQLException e) {
            log.warn("Could not query MAX(seq) from sync_log for {}: {}", serverId, e.getMessage());
            initialized = true;
        }
    }

    /**
     * Writes a sync_log entry using the caller's transaction connection.
     *
     * @return opId generated (origin-seq)
     */
    public String writeSyncLog(Connection conn, String tableName, String recordId,
                               String opType, int version, Object payload) throws SQLException {
        ensureInitialized();
        long nextSeq = seqCounter.incrementAndGet();
        String opId = serverId + "-" + nextSeq;
        String logId = UUID.randomUUID().toString();
        String payloadJson = payload instanceof String ? (String) payload : JsonUtil.toJson(payload);

        String sql = "INSERT INTO sync_log (id, origin, seq, op_id, table_name, record_id, op_type, version, payload_json, status, created_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', NOW())";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, logId);
            ps.setString(2, serverId);
            ps.setLong(3, nextSeq);
            ps.setString(4, opId);
            ps.setString(5, tableName);
            ps.setString(6, recordId);
            ps.setString(7, opType);
            ps.setInt(8, version);
            ps.setString(9, payloadJson);
            ps.executeUpdate();
        }

        log.debug("Wrote sync_log entry: opId={}, table={}, opType={}, version={}", opId, tableName, opType, version);

        if (priorityListener != null && isPriorityOperation(opType)) {
            try {
                priorityListener.onPriorityOperationLogged(opType, opId, nextSeq);
            } catch (Exception e) {
                log.warn("Error notifying priority listener for opId {}: {}", opId, e.getMessage());
            }
        }

        return opId;
    }

    /**
     * Writes an atomic EXAM_PACKAGE containing exams, exam_questions, and exam_assignments.
     */
    public String writeExamPackage(Connection conn, String examId, Object examData,
                                   List<?> questions, List<?> assignments, int version) throws SQLException {
        Map<String, Object> packagePayload = new HashMap<>();
        packagePayload.put("examId", examId);
        packagePayload.put("exam", examData);
        packagePayload.put("questions", questions);
        packagePayload.put("assignments", assignments);

        return writeSyncLog(conn, "exams", examId, "EXAM_PACKAGE", version, packagePayload);
    }

    public static boolean isPriorityOperation(String opType) {
        return "ATTEMPT_SUBMIT".equalsIgnoreCase(opType) || "ATTEMPT_CREATE".equalsIgnoreCase(opType);
    }

    public long getCurrentSeq() {
        return seqCounter.get();
    }

    public String getServerId() {
        return serverId;
    }
}
