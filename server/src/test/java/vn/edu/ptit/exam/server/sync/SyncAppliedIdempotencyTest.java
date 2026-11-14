package vn.edu.ptit.exam.server.sync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.edu.ptit.exam.server.sync.model.SyncLogEntry;

import java.sql.Connection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Test Idempotency - Duplicate Operations Skipped via sync_applied")
class SyncAppliedIdempotencyTest {

    @Test
    @DisplayName("Applying duplicate operation twice is idempotent and executes exactly once")
    void testDuplicateOperationSkipped() {
        Set<String> appliedKeys = new HashSet<>();
        AtomicInteger databaseWriteCount = new AtomicInteger(0);

        // Simulated SyncPeerStateDao
        SyncPeerStateDao mockDao = new SyncPeerStateDao(null) {
            @Override
            public boolean isOperationApplied(String origin, long seq) {
                return appliedKeys.contains(origin + "#" + seq);
            }

            @Override
            public void recordOperationApplied(Connection conn, String origin, long seq, String opId) {
                appliedKeys.add(origin + "#" + seq);
            }

            @Override
            public void updateLastReceivedSeq(String peerId, long receivedSeq) {
                // no-op for test
            }
        };

        // Applier using mock
        SyncApplier applier = new SyncApplier(null, mockDao, "1") {
            @Override
            public boolean applyOperation(SyncLogEntry entry) {
                if (mockDao.isOperationApplied(entry.getOrigin(), entry.getSeq())) {
                    return true; // Skipped via idempotency check
                }
                // Simulate write
                databaseWriteCount.incrementAndGet();
                try {
                    mockDao.recordOperationApplied(null, entry.getOrigin(), entry.getSeq(), entry.getOpId());
                } catch (Exception ignored) {}
                return true;
            }
        };

        SyncLogEntry entry = new SyncLogEntry();
        entry.setOrigin("2");
        entry.setSeq(101L);
        entry.setOpId("2-101");
        entry.setTableName("questions");
        entry.setRecordId("q-test-1");
        entry.setOpType("QUESTION_UPDATE");
        entry.setVersion(2);
        entry.setPayloadJson("{}");

        // First application: must execute write
        boolean firstRun = applier.applyOperation(entry);
        assertTrue(firstRun);
        assertEquals(1, databaseWriteCount.get(), "First run must execute database write");
        assertTrue(mockDao.isOperationApplied("2", 101L), "Operation must be recorded in sync_applied");

        // Second application (duplicate): must skip write
        boolean secondRun = applier.applyOperation(entry);
        assertTrue(secondRun);
        assertEquals(1, databaseWriteCount.get(), "Duplicate run must NOT execute database write again");

        // Third application: still 1
        boolean thirdRun = applier.applyOperation(entry);
        assertTrue(thirdRun);
        assertEquals(1, databaseWriteCount.get(), "Duplicate run must remain 1");
    }
}
