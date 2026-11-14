package vn.edu.ptit.exam.server.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.server.sync.model.SyncLogEntry;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Periodically or immediately polls PENDING sync logs and dispatches SYNC_BATCH to the 3 peers.
 * Batches up to 100 entries or every 200ms.
 */
public class SyncSender {

    private static final Logger log = LoggerFactory.getLogger(SyncSender.class);

    private final String localServerId;
    private final SyncPeerStateDao syncPeerStateDao;
    private final Map<String, SyncPeerConnection> peerConnections;
    private final int batchSize;
    private final long batchIntervalMs;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;

    public SyncSender(String localServerId, SyncPeerStateDao syncPeerStateDao,
                      Map<String, SyncPeerConnection> peerConnections,
                      int batchSize, long batchIntervalMs) {
        this.localServerId = localServerId;
        this.syncPeerStateDao = syncPeerStateDao;
        this.peerConnections = peerConnections;
        this.batchSize = batchSize;
        this.batchIntervalMs = batchIntervalMs;
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, localServerId + "-SyncSender-Thread");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleWithFixedDelay(this::dispatchPendingBatches, batchIntervalMs, batchIntervalMs, TimeUnit.MILLISECONDS);
            log.info("SyncSender started for {} with batchSize={}, interval={}ms", localServerId, batchSize, batchIntervalMs);
        }
    }

    public void stop() {
        running.set(false);
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * Immediately triggers dispatch without waiting for the 200ms interval.
     * Used for high-priority operations like ATTEMPT_SUBMIT and ATTEMPT_CREATE.
     */
    public void triggerImmediateDispatch() {
        if (running.get() && scheduler != null && !scheduler.isShutdown()) {
            scheduler.execute(this::dispatchPendingBatches);
        }
    }

    /**
     * Polls pending logs and dispatches batches to connected peers.
     */
    public void dispatchPendingBatches() {
        if (!running.get()) return;

        try {
            for (Map.Entry<String, SyncPeerConnection> entry : peerConnections.entrySet()) {
                String peerId = entry.getKey();
                SyncPeerConnection conn = entry.getValue();

                if (!conn.isConnected()) {
                    continue; // Skip disconnected peers, connection retry handles reconnect
                }

                // Query last acked sequence for this peer
                Map<String, vn.edu.ptit.exam.server.sync.model.SyncPeerStatus> states = syncPeerStateDao.getAllPeerStates();
                long lastAckedSeq = 0L;
                if (states.containsKey(peerId)) {
                    lastAckedSeq = states.get(peerId).getLastAckedSeq();
                }

                // Fetch pending batch
                List<SyncLogEntry> pendingBatch = syncPeerStateDao.getPendingLogsForPeer(localServerId, lastAckedSeq, batchSize);
                if (!pendingBatch.isEmpty()) {
                    conn.sendBatch(pendingBatch);
                }
            }

            // Check if all 3 peers have acked up to sequence K -> mark as DONE
            List<String> peerIds = new ArrayList<>(peerConnections.keySet());
            long minAckedSeq = syncPeerStateDao.getMinAckedSeqAcrossPeers(peerIds);
            if (minAckedSeq > 0) {
                int marked = syncPeerStateDao.markSyncLogsDone(localServerId, minAckedSeq);
                if (marked > 0) {
                    log.debug("Marked {} sync_log entries as DONE up to seq={}", marked, minAckedSeq);
                }
            }
        } catch (Exception e) {
            log.error("Error during sync batch dispatch for {}: {}", localServerId, e.getMessage(), e);
        }
    }
}
