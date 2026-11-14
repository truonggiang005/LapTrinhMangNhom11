package vn.edu.ptit.exam.server.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.server.db.DatabaseManager;
import vn.edu.ptit.exam.server.sync.model.SyncLogEntry;
import vn.edu.ptit.exam.server.sync.model.SyncPeerStatus;

import java.sql.*;
import java.util.*;

/**
 * DAO for sync_peer_state, sync_applied, and sync_log queries.
 */
public class SyncPeerStateDao {

    private static final Logger log = LoggerFactory.getLogger(SyncPeerStateDao.class);
    private final DatabaseManager dbManager;

    public SyncPeerStateDao(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    /**
     * Initializes default peer state rows for the other 3 peers if not existing.
     */
    public void initPeerStates(List<String> peerIds) {
        if (dbManager == null) return;
        String sql = "INSERT IGNORE INTO sync_peer_state (peer_id, last_received_seq, last_acked_seq, last_sync_time, status) " +
                     "VALUES (?, 0, 0, NOW(), 'ACTIVE')";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (String peerId : peerIds) {
                ps.setString(1, peerId);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            log.error("Failed to initialize sync_peer_state: {}", e.getMessage(), e);
        }
    }

    /**
     * Retrieves status for all peers.
     */
    public Map<String, SyncPeerStatus> getAllPeerStates() {
        Map<String, SyncPeerStatus> map = new HashMap<>();
        if (dbManager == null) return map;
        String sql = "SELECT peer_id, last_received_seq, last_acked_seq, last_sync_time, status FROM sync_peer_state";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String peerId = rs.getString("peer_id");
                long received = rs.getLong("last_received_seq");
                long acked = rs.getLong("last_acked_seq");
                Timestamp t = rs.getTimestamp("last_sync_time");
                String status = rs.getString("status");
                map.put(peerId, new SyncPeerStatus(peerId, status, received, acked, t != null ? t.getTime() : 0L, 0L));
            }
        } catch (SQLException e) {
            log.error("Failed to query sync_peer_state: {}", e.getMessage(), e);
        }
        return map;
    }

    /**
     * Updates the highest sequence number acknowledged by a peer.
     */
    public void updateLastAckedSeq(String peerId, long ackedSeq) {
        String sql = "UPDATE sync_peer_state SET last_acked_seq = GREATEST(last_acked_seq, ?), " +
                     "last_sync_time = NOW(), status = 'ACTIVE' WHERE peer_id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, ackedSeq);
            ps.setString(2, peerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to update last_acked_seq for peer {}: {}", peerId, e.getMessage(), e);
        }
    }

    /**
     * Updates the highest sequence number received and applied from a peer.
     */
    public void updateLastReceivedSeq(String peerId, long receivedSeq) {
        String sql = "UPDATE sync_peer_state SET last_received_seq = GREATEST(last_received_seq, ?), " +
                     "last_sync_time = NOW(), status = 'ACTIVE' WHERE peer_id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, receivedSeq);
            ps.setString(2, peerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to update last_received_seq for peer {}: {}", peerId, e.getMessage(), e);
        }
    }

    /**
     * Updates status of a peer (e.g. 'ACTIVE' or 'DOWN').
     */
    public void setPeerStatus(String peerId, String status) {
        String sql = "UPDATE sync_peer_state SET status = ? WHERE peer_id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, peerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to update status for peer {}: {}", peerId, e.getMessage(), e);
        }
    }

    /**
     * Checks if an operation has already been applied from (origin, seq).
     * Idempotency guarantee for Invariant 10.
     */
    public boolean isOperationApplied(String origin, long seq) {
        String sql = "SELECT 1 FROM sync_applied WHERE origin = ? AND seq = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, origin);
            ps.setLong(2, seq);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.error("Error checking sync_applied for origin={}, seq={}: {}", origin, seq, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Records that an operation (origin, seq, opId) has been applied.
     * Must be called in the same transaction as applying the operation!
     */
    public void recordOperationApplied(Connection conn, String origin, long seq, String opId) throws SQLException {
        String sql = "INSERT IGNORE INTO sync_applied (id, origin, seq, op_id, applied_at) VALUES (?, ?, ?, ?, NOW())";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, origin);
            ps.setLong(3, seq);
            ps.setString(4, opId);
            ps.executeUpdate();
        }
    }

    /**
     * Returns the minimum acked seq across all specified peers.
     * If any peer is missing or has 0, returns 0.
     */
    public long getMinAckedSeqAcrossPeers(List<String> peerIds) {
        if (peerIds == null || peerIds.isEmpty()) return 0L;
        String placeholders = String.join(",", Collections.nCopies(peerIds.size(), "?"));
        String sql = "SELECT COUNT(*), COALESCE(MIN(last_acked_seq), 0) FROM sync_peer_state WHERE peer_id IN (" + placeholders + ")";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < peerIds.size(); i++) {
                ps.setString(i + 1, peerIds.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int count = rs.getInt(1);
                    if (count < peerIds.size()) {
                        return 0L; // At least one peer hasn't been recorded yet
                    }
                    return rs.getLong(2);
                }
            }
        } catch (SQLException e) {
            log.error("Error querying min acked seq: {}", e.getMessage(), e);
        }
        return 0L;
    }

    /**
     * Marks sync_log rows as DONE when all peers have acknowledged up to upToSeq.
     */
    public int markSyncLogsDone(String origin, long upToSeq) {
        if (upToSeq <= 0) return 0;
        String sql = "UPDATE sync_log SET status = 'DONE' WHERE origin = ? AND seq <= ? AND status = 'PENDING'";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, origin);
            ps.setLong(2, upToSeq);
            return ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Error marking sync_log as DONE for origin={} upToSeq={}: {}", origin, upToSeq, e.getMessage(), e);
            return 0;
        }
    }

    /**
     * Cleans up DONE sync_log entries older than maxAgeHours (default 24h).
     */
    public int cleanupOldDoneLogs(int maxAgeHours) {
        String sql = "DELETE FROM sync_log WHERE status = 'DONE' AND created_at < DATE_SUB(NOW(), INTERVAL ? HOUR)";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, maxAgeHours);
            return ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Error cleaning up old DONE sync_logs: {}", e.getMessage(), e);
            return 0;
        }
    }

    /**
     * Reads a batch of pending logs for a peer starting after fromSeq.
     */
    public List<SyncLogEntry> getPendingLogsForPeer(String origin, long fromSeq, int limit) {
        String sql = "SELECT id, origin, seq, op_id, table_name, record_id, op_type, version, payload_json, status, created_at " +
                     "FROM sync_log WHERE origin = ? AND seq > ? " +
                     "ORDER BY seq ASC LIMIT ?";
        List<SyncLogEntry> list = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, origin);
            ps.setLong(2, fromSeq);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapSyncLog(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Error getting pending logs for origin={} after seq={}: {}", origin, fromSeq, e.getMessage(), e);
        }
        return list;
    }

    /**
     * Reads logs between fromSeq (exclusive) and toSeq (inclusive) for catch-up.
     */
    public List<SyncLogEntry> getLogsRange(String origin, long fromSeq, long toSeq, int limit) {
        String sql = "SELECT id, origin, seq, op_id, table_name, record_id, op_type, version, payload_json, status, created_at " +
                     "FROM sync_log WHERE origin = ? AND seq > ? AND seq <= ? " +
                     "ORDER BY seq ASC LIMIT ?";
        List<SyncLogEntry> list = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, origin);
            ps.setLong(2, fromSeq);
            ps.setLong(3, toSeq);
            ps.setInt(4, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapSyncLog(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Error getting log range: {}", e.getMessage(), e);
        }
        return list;
    }

    /**
     * Counts pending logs for origin.
     */
    public long countPendingLogs(String origin) {
        String sql = "SELECT COUNT(*) FROM sync_log WHERE origin = ? AND status = 'PENDING'";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, origin);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (SQLException e) {
            log.error("Error counting pending logs: {}", e.getMessage(), e);
        }
        return 0L;
    }

    /**
     * Returns a map of origin -> highest applied sequence number seen by this server.
     */
    public Map<String, Long> getLastSeenSeqs() {
        Map<String, Long> map = new HashMap<>();
        String sql = "SELECT origin, COALESCE(MAX(seq), 0) FROM sync_applied GROUP BY origin";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                map.put(rs.getString(1), rs.getLong(2));
            }
        } catch (SQLException e) {
            log.error("Error querying last seen seqs: {}", e.getMessage(), e);
        }
        return map;
    }

    /**
     * Checks if old logs before fromSeq were already deleted (detects purge).
     */
    public boolean wereLogsPurged(String origin, long peerLastSeenSeq) {
        String sql = "SELECT COALESCE(MIN(seq), 1) FROM sync_log WHERE origin = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, origin);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long minSeq = rs.getLong(1);
                    return minSeq > peerLastSeenSeq + 1;
                }
            }
        } catch (SQLException e) {
            log.error("Error checking log purge: {}", e.getMessage(), e);
        }
        return false;
    }

    private SyncLogEntry mapSyncLog(ResultSet rs) throws SQLException {
        Timestamp ts = rs.getTimestamp("created_at");
        return new SyncLogEntry(
                rs.getString("id"),
                rs.getString("origin"),
                rs.getLong("seq"),
                rs.getString("op_id"),
                rs.getString("table_name"),
                rs.getString("record_id"),
                rs.getString("op_type"),
                rs.getInt("version"),
                rs.getString("payload_json"),
                rs.getString("status"),
                ts != null ? ts.getTime() : System.currentTimeMillis()
        );
    }
}
