package vn.edu.ptit.exam.server.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.server.db.DatabaseManager;
import vn.edu.ptit.exam.server.sync.model.ConsistencyReport;
import vn.edu.ptit.exam.server.sync.model.TableHashReport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically checks data consistency across all 4 servers (coordinator runs on Server 2).
 * Compares row count and SHA-256 hash of sorted (id, version) records per table.
 */
public class ConsistencyChecker {

    private static final Logger log = LoggerFactory.getLogger(ConsistencyChecker.class);

    private static final String[] MONITORED_TABLES = {"users", "questions", "exams", "attempts", "attempt_answers"};

    private final DatabaseManager dbManager;
    private final String localServerId;
    private final boolean isCoordinator;
    private final long intervalMs;

    private ScheduledExecutorService scheduler;
    private volatile ConsistencyReport lastReport;
    private ConsistencyCheckRequester peerRequester;

    public interface ConsistencyCheckRequester {
        Map<String, TableHashReport> requestPeerHashes(String peerId);
    }

    public ConsistencyChecker(DatabaseManager dbManager, String localServerId, boolean isCoordinator, long intervalMs) {
        this.dbManager = dbManager;
        this.localServerId = localServerId;
        this.isCoordinator = isCoordinator;
        this.intervalMs = intervalMs;
    }

    public void setPeerRequester(ConsistencyCheckRequester peerRequester) {
        this.peerRequester = peerRequester;
    }

    public void start() {
        if (isCoordinator) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, localServerId + "-Consistency-Coordinator");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleAtFixedRate(this::runCheck, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
            log.info("ConsistencyChecker coordinator started on {} with interval {}ms", localServerId, intervalMs);
        }
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * Executes consistency verification across all servers.
     */
    public ConsistencyReport runCheck() {
        long now = System.currentTimeMillis();
        Map<String, TableHashReport> localReports = computeLocalTableHashes();
        Map<String, Map<String, TableHashReport>> allServerReports = new HashMap<>();
        allServerReports.put(localServerId, localReports);

        if (peerRequester != null) {
            List<String> peers = Arrays.asList("1", "2", "3", "4");
            for (String peerId : peers) {
                if (peerId.equals(localServerId)) continue;
                try {
                    Map<String, TableHashReport> peerReports = peerRequester.requestPeerHashes(peerId);
                    if (peerReports != null && !peerReports.isEmpty()) {
                        allServerReports.put(peerId, peerReports);
                    }
                } catch (Exception e) {
                    log.warn("Could not fetch consistency report from peer {}: {}", peerId, e.getMessage());
                }
            }
        }

        boolean allMatch = true;
        StringBuilder mismatchBuilder = new StringBuilder();

        for (String table : MONITORED_TABLES) {
            TableHashReport reference = localReports.get(table);
            if (reference == null) continue;

            for (Map.Entry<String, Map<String, TableHashReport>> entry : allServerReports.entrySet()) {
                String sid = entry.getKey();
                if (sid.equals(localServerId)) continue;

                TableHashReport peerTable = entry.getValue().get(table);
                if (peerTable == null) {
                    allMatch = false;
                    mismatchBuilder.append(String.format("Table %s missing on server %s; ", table, sid));
                    continue;
                }

                if (reference.getRowCount() != peerTable.getRowCount() ||
                        !Objects.equals(reference.getHash(), peerTable.getHash())) {
                    allMatch = false;
                    String details = String.format("Table %s mismatch: local server %s [count=%d, hash=%s] vs server %s [count=%d, hash=%s]; ",
                            table, localServerId, reference.getRowCount(), reference.getHash(),
                            sid, peerTable.getRowCount(), peerTable.getHash());
                    mismatchBuilder.append(details);
                    log.warn("CONSISTENCY MISMATCH: {}", details);
                }
            }
        }

        if (allMatch) {
            log.info("Consistency check PASSED across all {} responsive servers at {}", allServerReports.size(), now);
        } else {
            log.warn("Consistency check FAILED: {}", mismatchBuilder);
        }

        ConsistencyReport report = new ConsistencyReport(
                now,
                allMatch,
                localReports,
                allMatch ? "Tất cả bảng đều nhất quán giữa các Server" : mismatchBuilder.toString()
        );
        this.lastReport = report;
        return report;
    }

    /**
     * Computes row count and SHA-256 hash for local tables.
     */
    public Map<String, TableHashReport> computeLocalTableHashes() {
        Map<String, TableHashReport> map = new HashMap<>();
        for (String table : MONITORED_TABLES) {
            try {
                map.put(table, computeTableHash(table));
            } catch (Exception e) {
                log.error("Error computing hash for table {}: {}", table, e.getMessage());
                map.put(table, new TableHashReport(table, 0, "ERROR: " + e.getMessage()));
            }
        }
        return map;
    }

    private TableHashReport computeTableHash(String tableName) throws SQLException, NoSuchAlgorithmException {
        String sql = "SELECT id, version FROM " + tableName + " ORDER BY id ASC";
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        long count = 0;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                count++;
                String id = rs.getString(1);
                int version = rs.getInt(2);
                String line = id + ":" + version + "\n";
                digest.update(line.getBytes(StandardCharsets.UTF_8));
            }
        }

        byte[] hashBytes = digest.digest();
        StringBuilder hexString = new StringBuilder();
        for (byte b : hashBytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }

        return new TableHashReport(tableName, count, hexString.toString());
    }

    public ConsistencyReport getLastReport() {
        return lastReport;
    }
}
