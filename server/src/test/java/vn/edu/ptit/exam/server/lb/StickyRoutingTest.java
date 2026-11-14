package vn.edu.ptit.exam.server.lb;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class StickyRoutingTest {

    @Test
    void testExamRegistryStickiness() {
        ExamRegistry registry = new ExamRegistry();

        // 1. Register attempt for user sv01 in exam 1 on server 2
        registry.registerExam("sv01", "1", "attempt-uuid-101", 2);

        // Invariant 8: Sticky routing by attemptId
        Optional<Integer> serverForAttempt = registry.getServerForAttempt("attempt-uuid-101");
        assertTrue(serverForAttempt.isPresent());
        assertEquals(2, serverForAttempt.get());

        // Sticky routing by (userId, examId)
        Optional<Integer> serverForUserExam = registry.getServerForUserExam("sv01", "1");
        assertTrue(serverForUserExam.isPresent());
        assertEquals(2, serverForUserExam.get());

        // Unknown attempt returns empty
        assertFalse(registry.getServerForAttempt("unknown-attempt").isPresent());

        // 2. Failover reassignment: reassign attempt to server 4
        registry.reassignServer("attempt-uuid-101", 4);
        assertEquals(4, registry.getServerForAttempt("attempt-uuid-101").get());
        assertEquals(4, registry.getServerForUserExam("sv01", "1").get());

        // 3. Cleanup on submit/finalize
        registry.removeAttempt("attempt-uuid-101");
        assertFalse(registry.getServerForAttempt("attempt-uuid-101").isPresent());
    }
}
