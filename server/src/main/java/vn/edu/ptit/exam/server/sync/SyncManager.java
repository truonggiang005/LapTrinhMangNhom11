package vn.edu.ptit.exam.server.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.common.config.Config;
import vn.edu.ptit.exam.server.db.DatabaseManager;
import vn.edu.ptit.exam.server.sync.model.ConsistencyReport;
import vn.edu.ptit.exam.server.sync.model.SyncPeerStatus;
import vn.edu.ptit.exam.server.sync.model.SyncStatus;
import vn.edu.ptit.exam.server.sync.model.TableHashReport;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Master coordinator for Server Data Synchronization (package sync, Luồng 6).
 * Integrates SyncLogWriter, SyncPeerStateDao, SyncApplier, SyncServer,
 * outgoing SyncPeerConnections, SyncSender, and ConsistencyChecker.
 */
public class SyncManager {

    private static final Logger log = LoggerFactory.getLogger(SyncManager.class);

    private final int serverIndex;
    private final String serverId;
    private final DatabaseManager dbManager;

    private final SyncLogWriter syncLogWriter;
    private final SyncPeerStateDao syncPeerStateDao;
    private final SyncApplier syncApplier;
    private final SyncServer syncServer;
    private final ConsistencyChecker consistencyChecker;
    private final SyncSender syncSender;

    private final Map<String, SyncPeerConnection> peerConnections = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Replication wait tracking: opId -> ReplicationTracker
    private final ConcurrentHashMap<String, ReplicationTracker> replicationTrackers = new ConcurrentHashMap<>();

    // Background executor for periodic 24-hour log cleanup
    private ScheduledExecutorService cleanupScheduler;

    private static class ReplicationTracker {
        final String opId;
        final long seq;
        final int minAcks;
        final Set<String> ackedPeers = ConcurrentHashMap.newKeySet();
        final CompletableFuture<Boolean> future = new CompletableFuture<>();

        ReplicationTracker(String opId, long seq, int minAcks) {
            this.opId = opId;
            this.seq = seq;
            this.minAcks = minAcks;
        }
    }

    public SyncManager(int serverIndex, String serverId, DatabaseManager dbManager, SyncLogWriter syncLogWriter) {
        this(serverIndex, serverId, dbManager, syncLogWriter, new SyncPeerStateDao(dbManager));
    }

    public SyncManager(int serverIndex, String serverId, DatabaseManager dbManager, SyncLogWriter syncLogWriter,
                       SyncPeerStateDao syncPeerStateDao) {
        this.serverIndex = serverIndex;
        this.serverId = serverId;
        this.dbManager = dbManager;
        this.syncLogWriter = syncLogWriter;

        Config config = Config.getInstance();
        this.syncPeerStateDao = syncPeerStateDao != null ? syncPeerStateDao : new SyncPeerStateDao(dbManager);
        this.syncApplier = new SyncApplier(dbManager, this.syncPeerStateDao, serverId);

        boolean isCoordinator = (serverIndex == 2);
        long consistencyInterval = config.getSyncConsistencyCheckIntervalMs();
        this.consistencyChecker = new ConsistencyChecker(dbManager, serverId, isCoordinator, consistencyInterval);

        int syncPort = config.getServerSyncPort(serverIndex);
        this.syncServer = new SyncServer(serverId, syncPort, syncApplier, this.syncPeerStateDao, consistencyChecker);

        // Initialize peer connections to other 3 servers
        List<String> peerIds = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            if (i != serverIndex) {
                String pId = config.getServerId(i);
                peerIds.add(pId);
                SyncPeerConnection conn = new SyncPeerConnection(serverId, i, syncPeerStateDao, this::onPeerAckReceived);
                peerConnections.put(pId, conn);
            }
        }

        // Initialize peer states in database
        syncPeerStateDao.initPeerStates(peerIds);

        // Configure SyncSender
        int batchSize = config.getSyncBatchSize();
        long batchInterval = config.getSyncBatchIntervalMs();
        this.syncSender = new SyncSender(serverId, syncPeerStateDao, peerConnections, batchSize, batchInterval);

        // Set priority listener on SyncLogWriter to trigger immediate dispatch on ATTEMPT_SUBMIT / ATTEMPT_CREATE
        this.syncLogWriter.setPriorityListener((opType, opId, seq) -> {
            log.debug("Priority sync op {} logged with id={}, triggering immediate sync", opType, opId);
            syncSender.triggerImmediateDispatch();
        });

        // Set peer requester on consistency checker coordinator
        if (isCoordinator) {
            this.consistencyChecker.setPeerRequester(this::requestPeerTableHashes);
        }
    }

    public synchronized void start() throws IOException {
        if (running.compareAndSet(false, true)) {
            log.info("Starting SyncManager on server {}...", serverId);

            // 1. Start incoming sync server
            syncServer.start();

            // 2. Start outgoing peer connections
            for (SyncPeerConnection peerConn : peerConnections.values()) {
                peerConn.start();
            }

            // 3. Start batch sender
            syncSender.start();

            // 4. Start consistency checker
            consistencyChecker.start();

            // 5. Schedule periodic cleanup for DONE logs older than 24 hours (runs every 1 hour)
            cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, serverId + "-SyncCleaner");
                t.setDaemon(true);
                return t;
            });
            cleanupScheduler.scheduleAtFixedRate(() -> {
                try {
                    int cleaned = syncPeerStateDao.cleanupOldDoneLogs(24);
                    if (cleaned > 0) {
                        log.info("Cleaned up {} old DONE sync_logs older than 24 hours on {}", cleaned, serverId);
                    }
                } catch (Exception e) {
                    log.error("Error during sync_log cleanup on {}: {}", serverId, e.getMessage());
                }
            }, 1, 1, TimeUnit.HOURS);

            log.info("SyncManager successfully started on server {}", serverId);
        }
    }

    public synchronized void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping SyncManager on server {}...", serverId);
            syncSender.stop();
            consistencyChecker.stop();
            for (SyncPeerConnection conn : peerConnections.values()) {
                conn.stop();
            }
            syncServer.stop();
            if (cleanupScheduler != null) {
                cleanupScheduler.shutdownNow();
            }
        }
    }

    /**
     * Waits for replication of opId to at least minAcks peer servers within timeoutSec.
     * Luồng 5 Bước 6: waitForReplication(opId, minAcks = 1, timeout = 2s).
     *
     * @return true if replicated to >= minAcks peers, false if timed out
     */
    public boolean waitForReplication(String opId, int minAcks, long timeoutMs) {
        if (opId == null || opId.isEmpty()) return true;

        long seq = parseSeqFromOpId(opId);
        ReplicationTracker tracker = new ReplicationTracker(opId, seq, minAcks);

        // Check if already acknowledged by any peers
        Map<String, SyncPeerStatus> states = syncPeerStateDao.getAllPeerStates();
        for (Map.Entry<String, SyncPeerStatus> entry : states.entrySet()) {
            if (entry.getValue().getLastAckedSeq() >= seq) {
                tracker.ackedPeers.add(entry.getKey());
            }
        }

        if (tracker.ackedPeers.size() >= minAcks) {
            return true;
        }

        replicationTrackers.put(opId, tracker);

        // Trigger immediate dispatch so peer gets the batch ASAP
        syncSender.triggerImmediateDispatch();

        try {
            Boolean result = tracker.future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return result != null && result;
        } catch (TimeoutException te) {
            log.warn("waitForReplication timed out after {}ms for opId={} (acked by {}/{} peers)",
                    timeoutMs, opId, tracker.ackedPeers.size(), minAcks);
            return false;
        } catch (Exception e) {
            log.error("Error during waitForReplication for opId {}: {}", opId, e.getMessage());
            return false;
        } finally {
            replicationTrackers.remove(opId);
        }
    }

    /**
     * Checks how many peer servers have acknowledged opId.
     * Luồng 7 Bước 6: isReplicated(opId) trả số Server đã xác nhận (0..3).
     */
    public int isReplicated(String opId) {
        if (opId == null) return 0;
        long seq = parseSeqFromOpId(opId);
        int ackCount = 0;
        Map<String, SyncPeerStatus> states = syncPeerStateDao.getAllPeerStates();
        for (SyncPeerStatus s : states.values()) {
            if (s.getLastAckedSeq() >= seq) {
                ackCount++;
            }
        }
        return ackCount;
    }

    /**
     * Returns true if opId has been acknowledged by all other 3 peers.
     */
    public boolean isFullyReplicated(String opId) {
        return isReplicated(opId) >= 3;
    }

    private void onPeerAckReceived(String peerId, String origin, long ackedSeq) {
        // Notify any active replication trackers waiting for this origin's seq
        if (serverId.equals(origin)) {
            for (ReplicationTracker tracker : replicationTrackers.values()) {
                if (tracker.seq <= ackedSeq) {
                    tracker.ackedPeers.add(peerId);
                    if (tracker.ackedPeers.size() >= tracker.minAcks) {
                        tracker.future.complete(true);
                    }
                }
            }
        }
    }

    /**
     * Returns the comprehensive sync status for administration and monitoring.
     */
    public SyncStatus getSyncStatus() {
        long curSeq = syncLogWriter.getCurrentSeq();
        long pending = syncPeerStateDao.countPendingLogs(serverId);
        Map<String, SyncPeerStatus> peerStates = syncPeerStateDao.getAllPeerStates();

        // Enrich with real-time latency
        for (Map.Entry<String, SyncPeerStatus> entry : peerStates.entrySet()) {
            SyncPeerConnection conn = peerConnections.get(entry.getKey());
            if (conn != null) {
                entry.getValue().setLatencyMs(conn.getLatencyMs());
                if (!conn.isConnected()) {
                    entry.getValue().setStatus("DOWN");
                }
            }
        }

        ConsistencyReport report = consistencyChecker.getLastReport();
        return new SyncStatus(serverId, curSeq, pending, peerStates, report);
    }

    private Map<String, TableHashReport> requestPeerTableHashes(String peerId) {
        // Can be called directly or via TCP
        SyncPeerConnection conn = peerConnections.get(peerId);
        if (conn == null || !conn.isConnected()) {
            return Collections.emptyMap();
        }
        return Collections.emptyMap();
    }

    private long parseSeqFromOpId(String opId) {
        try {
            int idx = opId.lastIndexOf('-');
            if (idx >= 0 && idx < opId.length() - 1) {
                return Long.parseLong(opId.substring(idx + 1));
            }
        } catch (Exception ignored) {}
        return 0L;
    }

    public SyncLogWriter getSyncLogWriter() {
        return syncLogWriter;
    }

    public SyncPeerStateDao getSyncPeerStateDao() {
        return syncPeerStateDao;
    }

    public SyncApplier getSyncApplier() {
        return syncApplier;
    }

    public ConsistencyChecker getConsistencyChecker() {
        return consistencyChecker;
    }

    public SyncSender getSyncSender() {
        return syncSender;
    }
}
