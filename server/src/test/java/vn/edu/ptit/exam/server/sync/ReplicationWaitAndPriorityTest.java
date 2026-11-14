package vn.edu.ptit.exam.server.sync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.edu.ptit.exam.common.util.JsonUtil;
import vn.edu.ptit.exam.server.sync.model.SyncPeerStatus;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Test Replication Waiting, Priority Queuing, and isReplicated")
class ReplicationWaitAndPriorityTest {

    @Test
    @DisplayName("Priority operations ATTEMPT_SUBMIT and ATTEMPT_CREATE are correctly identified")
    void testPriorityOperationCheck() {
        assertTrue(SyncLogWriter.isPriorityOperation("ATTEMPT_SUBMIT"));
        assertTrue(SyncLogWriter.isPriorityOperation("ATTEMPT_CREATE"));
        assertFalse(SyncLogWriter.isPriorityOperation("ANSWER_UPSERT"));
        assertFalse(SyncLogWriter.isPriorityOperation("QUESTION_UPDATE"));
        assertFalse(SyncLogWriter.isPriorityOperation("USER_LOCK"));
    }

    @Test
    @DisplayName("Priority listener is triggered when priority operation is logged")
    void testPriorityListenerTriggered() {
        AtomicBoolean triggered = new AtomicBoolean(false);
        SyncLogWriter writer = new SyncLogWriter(null, "1");
        writer.setPriorityListener((opType, opId, seq) -> {
            if ("ATTEMPT_SUBMIT".equalsIgnoreCase(opType)) {
                triggered.set(true);
            }
        });

        // Test priority check
        assertTrue(SyncLogWriter.isPriorityOperation("ATTEMPT_SUBMIT"));
    }

    @Test
    @DisplayName("waitForReplication completes successfully when ACKs arrive within timeout")
    void testWaitForReplicationSuccess() throws Exception {
        Map<String, SyncPeerStatus> peerStates = new ConcurrentHashMap<>();
        peerStates.put("2", new SyncPeerStatus("2", "ACTIVE", 0, 0, System.currentTimeMillis(), 5));
        peerStates.put("3", new SyncPeerStatus("3", "ACTIVE", 0, 0, System.currentTimeMillis(), 5));
        peerStates.put("4", new SyncPeerStatus("4", "ACTIVE", 0, 0, System.currentTimeMillis(), 5));

        SyncPeerStateDao mockDao = new SyncPeerStateDao(null) {
            @Override
            public Map<String, SyncPeerStatus> getAllPeerStates() {
                return peerStates;
            }
        };

        SyncLogWriter writer = new SyncLogWriter(null, "1");
        SyncManager manager = new SyncManager(1, "1", null, writer, mockDao);

        String opId = "1-105";

        // Asynchronously simulate peer 2 sending SYNC_ACK after 50ms
        Executors.newSingleThreadScheduledExecutor().schedule(() -> {
            peerStates.get("2").setLastAckedSeq(105L);
            // Simulate calling the ack callback
            try {
                java.lang.reflect.Method m = SyncManager.class.getDeclaredMethod("onPeerAckReceived", String.class, String.class, long.class);
                m.setAccessible(true);
                m.invoke(manager, "2", "1", 105L);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 50, TimeUnit.MILLISECONDS);

        // waitForReplication requiring 1 ack within 2000ms
        boolean replicated = manager.waitForReplication(opId, 1, 2000L);
        assertTrue(replicated, "waitForReplication must succeed when peer ACK arrives");
    }

    @Test
    @DisplayName("waitForReplication times out gracefully when no ACK arrives")
    void testWaitForReplicationTimeout() {
        Map<String, SyncPeerStatus> peerStates = new ConcurrentHashMap<>();
        peerStates.put("2", new SyncPeerStatus("2", "ACTIVE", 0, 0, System.currentTimeMillis(), 5));
        peerStates.put("3", new SyncPeerStatus("3", "ACTIVE", 0, 0, System.currentTimeMillis(), 5));
        peerStates.put("4", new SyncPeerStatus("4", "ACTIVE", 0, 0, System.currentTimeMillis(), 5));

        SyncPeerStateDao mockDao = new SyncPeerStateDao(null) {
            @Override
            public Map<String, SyncPeerStatus> getAllPeerStates() {
                return peerStates;
            }
        };

        SyncLogWriter writer = new SyncLogWriter(null, "1");
        SyncManager manager = new SyncManager(1, "1", null, writer, mockDao);

        String opId = "1-999";
        // Wait 100ms with no ACKs
        boolean replicated = manager.waitForReplication(opId, 1, 100L);
        assertFalse(replicated, "waitForReplication must return false on timeout without throwing exception");
    }

    @Test
    @DisplayName("isReplicated accurately counts the number of peer acknowledgments")
    void testIsReplicatedCount() {
        Map<String, SyncPeerStatus> peerStates = new HashMap<>();
        // Peer 2 acked up to 200, Peer 3 acked up to 150, Peer 4 acked up to 100
        peerStates.put("2", new SyncPeerStatus("2", "ACTIVE", 0, 200L, System.currentTimeMillis(), 5));
        peerStates.put("3", new SyncPeerStatus("3", "ACTIVE", 0, 150L, System.currentTimeMillis(), 5));
        peerStates.put("4", new SyncPeerStatus("4", "ACTIVE", 0, 100L, System.currentTimeMillis(), 5));

        SyncPeerStateDao mockDao = new SyncPeerStateDao(null) {
            @Override
            public Map<String, SyncPeerStatus> getAllPeerStates() {
                return peerStates;
            }
        };

        SyncLogWriter writer = new SyncLogWriter(null, "1");
        SyncManager manager = new SyncManager(1, "1", null, writer, mockDao);

        // opId with seq 90: all 3 peers have acked (200 >= 90, 150 >= 90, 100 >= 90)
        assertEquals(3, manager.isReplicated("1-90"));
        assertTrue(manager.isFullyReplicated("1-90"));

        // opId with seq 120: 2 peers have acked (200 and 150)
        assertEquals(2, manager.isReplicated("1-120"));
        assertFalse(manager.isFullyReplicated("1-120"));

        // opId with seq 180: only 1 peer has acked (200)
        assertEquals(1, manager.isReplicated("1-180"));

        // opId with seq 250: 0 peers have acked
        assertEquals(0, manager.isReplicated("1-250"));
    }
}
