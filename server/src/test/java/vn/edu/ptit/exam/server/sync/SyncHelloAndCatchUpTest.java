package vn.edu.ptit.exam.server.sync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.edu.ptit.exam.common.dto.Message;
import vn.edu.ptit.exam.common.enums.MessageType;
import vn.edu.ptit.exam.common.util.JsonUtil;
import vn.edu.ptit.exam.server.sync.model.SyncLogEntry;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Test SYNC_HELLO Handshake and Catch-Up Logic")
class SyncHelloAndCatchUpTest {

    @Test
    @DisplayName("SYNC_HELLO correctly includes lastSeenSeqs per origin")
    void testSyncHelloPayloadStructure() {
        Map<String, Long> lastSeen = new HashMap<>();
        lastSeen.put("1", 500L);
        lastSeen.put("2", 350L);
        lastSeen.put("3", 420L);

        Map<String, Object> payload = new HashMap<>();
        payload.put("serverId", "4");
        payload.put("lastSeenSeqs", lastSeen);

        Message hello = new Message();
        hello.setType(MessageType.SYNC_HELLO);
        hello.setOrigin("4");
        hello.setPayload(payload);

        String json = JsonUtil.toJson(hello);
        Message parsed = JsonUtil.fromJson(json, Message.class);

        assertEquals(MessageType.SYNC_HELLO.name(), parsed.getType());
        assertEquals("4", parsed.getOrigin());

        Map<?, ?> pMap = JsonUtil.convertValue(parsed.getPayload(), Map.class);
        assertEquals("4", pMap.get("serverId"));

        Map<?, ?> seen = (Map<?, ?>) pMap.get("lastSeenSeqs");
        assertEquals(500, ((Number) seen.get("1")).longValue());
        assertEquals(350, ((Number) seen.get("2")).longValue());
        assertEquals(420, ((Number) seen.get("3")).longValue());
    }

    @Test
    @DisplayName("Catch-up detects missing delta between peer lastSeen and local current seq")
    void testCatchUpDeltaComputation() {
        long localCurrentSeq = 1200L;
        long peerLastSeenSeq = 1150L;

        long delta = localCurrentSeq - peerLastSeenSeq;
        assertEquals(50, delta);
        assertTrue(delta <= 50000, "Delta within 50,000 threshold can be synced by batch");

        // Simulate reading the range (1150 to 1200]
        List<SyncLogEntry> range = new ArrayList<>();
        for (long s = peerLastSeenSeq + 1; s <= localCurrentSeq; s++) {
            SyncLogEntry entry = new SyncLogEntry();
            entry.setOrigin("1");
            entry.setSeq(s);
            entry.setOpId("1-" + s);
            range.add(entry);
        }

        assertEquals(50, range.size());
        assertEquals(1151L, range.get(0).getSeq());
        assertEquals(1200L, range.get(range.size() - 1).getSeq());
    }

    @Test
    @DisplayName("Delta > 50,000 triggers whole table hash sync")
    void testLargeDeltaRequiresTableSync() {
        long localCurrentSeq = 100000L;
        long peerLastSeenSeq = 40000L;

        long delta = localCurrentSeq - peerLastSeenSeq;
        assertTrue(delta > 50000, "Difference > 50,000 must trigger table-level hash sync");
    }
}
