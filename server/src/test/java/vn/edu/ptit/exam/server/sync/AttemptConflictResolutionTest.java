package vn.edu.ptit.exam.server.sync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.edu.ptit.exam.server.sync.SyncApplier.ConflictWinner;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Test Invariant 10 - Attempt and Answers Conflict Resolution")
class AttemptConflictResolutionTest {

    @Test
    @DisplayName("SUBMITTED status cannot be overwritten by IN_PROGRESS")
    void testSubmittedCannotBeOverwrittenByInProgress() {
        // Local is SUBMITTED, incoming is IN_PROGRESS -> Local wins!
        ConflictWinner winner1 = SyncApplier.resolveAttemptConflict("IN_PROGRESS", null, "SUBMITTED", 1000L);
        assertEquals(ConflictWinner.LOCAL_WINS, winner1);

        // Local is IN_PROGRESS, incoming is SUBMITTED -> Incoming wins!
        ConflictWinner winner2 = SyncApplier.resolveAttemptConflict("SUBMITTED", 2000L, "IN_PROGRESS", null);
        assertEquals(ConflictWinner.INCOMING_WINS, winner2);
    }

    @Test
    @DisplayName("Two SUBMITTED attempts: earlier submit_time wins")
    void testTwoSubmittedEarlierSubmitTimeWins() {
        long tEarly = 1700000000000L;
        long tLate = 1700000005000L;

        // Incoming submit_time is earlier (tEarly < tLate) -> Incoming wins
        ConflictWinner winner1 = SyncApplier.resolveAttemptConflict("SUBMITTED", tEarly, "SUBMITTED", tLate);
        assertEquals(ConflictWinner.INCOMING_WINS, winner1);

        // Local submit_time is earlier (tEarly < tLate) -> Local wins
        ConflictWinner winner2 = SyncApplier.resolveAttemptConflict("SUBMITTED", tLate, "SUBMITTED", tEarly);
        assertEquals(ConflictWinner.LOCAL_WINS, winner2);

        // Equal submit_time -> Local wins to avoid redundant writes
        ConflictWinner winner3 = SyncApplier.resolveAttemptConflict("SUBMITTED", tEarly, "SUBMITTED", tEarly);
        assertEquals(ConflictWinner.LOCAL_WINS, winner3);
    }

    @Test
    @DisplayName("Answers updated after submit_time of SUBMITTED attempt are discarded")
    void testDiscardAnswersAfterSubmitTime() {
        long submitTime = 1700000010000L;
        long answerBeforeSubmit = 1700000005000L;
        long answerAfterSubmit = 1700000012000L;

        // Answer after submit_time on a SUBMITTED attempt -> Discard!
        boolean discard1 = SyncApplier.shouldDiscardAnswer(true, submitTime, answerAfterSubmit);
        assertTrue(discard1, "Answers after submit_time must be discarded");

        // Answer before submit_time on a SUBMITTED attempt -> Keep!
        boolean discard2 = SyncApplier.shouldDiscardAnswer(true, submitTime, answerBeforeSubmit);
        assertFalse(discard2, "Answers before submit_time should not be discarded");

        // Answer on an IN_PROGRESS attempt -> Keep!
        boolean discard3 = SyncApplier.shouldDiscardAnswer(false, submitTime, answerAfterSubmit);
        assertFalse(discard3, "Answers on IN_PROGRESS attempt should not be discarded");
    }

    @Test
    @DisplayName("For valid answers, later updated_at wins")
    void testLaterAnswerWins() {
        long t1 = 1000L;
        long t2 = 2000L;

        assertEquals(ConflictWinner.INCOMING_WINS, SyncApplier.resolveAnswerConflict(t2, t1));
        assertEquals(ConflictWinner.LOCAL_WINS, SyncApplier.resolveAnswerConflict(t1, t2));
        assertEquals(ConflictWinner.INCOMING_WINS, SyncApplier.resolveAnswerConflict(t2, t2));
    }
}
