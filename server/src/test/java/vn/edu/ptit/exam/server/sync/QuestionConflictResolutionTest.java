package vn.edu.ptit.exam.server.sync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.edu.ptit.exam.server.sync.SyncApplier.ConflictWinner;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Test Invariant 10 - Catalog Conflict Resolution (Questions)")
class QuestionConflictResolutionTest {

    @Test
    @DisplayName("Higher version wins over lower version regardless of origin server")
    void testHigherVersionWins() {
        // Incoming version 5 from Server 3 vs Local version 4 on Server 1 -> Incoming wins
        ConflictWinner winner1 = SyncApplier.resolveCatalogConflict(5, "3", 4, "1");
        assertEquals(ConflictWinner.INCOMING_WINS, winner1);

        // Incoming version 3 from Server 1 vs Local version 4 on Server 2 -> Local wins
        ConflictWinner winner2 = SyncApplier.resolveCatalogConflict(3, "1", 4, "2");
        assertEquals(ConflictWinner.LOCAL_WINS, winner2);
    }

    @Test
    @DisplayName("When versions are equal, lower origin server ID wins (Tie-breaker)")
    void testEqualVersionTieBreakerLowerOriginWins() {
        // Both version 2: Incoming origin "1" vs Local origin "2" -> Server 1 has lower ID -> Incoming wins
        ConflictWinner winner1 = SyncApplier.resolveCatalogConflict(2, "1", 2, "2");
        assertEquals(ConflictWinner.INCOMING_WINS, winner1);

        // Both version 2: Incoming origin "3" vs Local origin "2" -> Server 2 has lower ID -> Local wins
        ConflictWinner winner2 = SyncApplier.resolveCatalogConflict(2, "3", 2, "2");
        assertEquals(ConflictWinner.LOCAL_WINS, winner2);

        // Both version 1: Server 1 vs Server 4 -> Server 1 wins
        ConflictWinner winner3 = SyncApplier.resolveCatalogConflict(1, "4", 1, "1");
        assertEquals(ConflictWinner.LOCAL_WINS, winner3);
    }

    @Test
    @DisplayName("Origin comparison handles various naming formats (1 vs 2, server-1 vs server-2)")
    void testCompareOriginFormats() {
        assertTrue(SyncApplier.compareOrigin("1", "2") < 0);
        assertTrue(SyncApplier.compareOrigin("2", "1") > 0);
        assertEquals(0, SyncApplier.compareOrigin("1", "1"));

        assertTrue(SyncApplier.compareOrigin("server-1", "server-2") < 0);
        assertTrue(SyncApplier.compareOrigin("server-3", "server-2") > 0);
    }
}
