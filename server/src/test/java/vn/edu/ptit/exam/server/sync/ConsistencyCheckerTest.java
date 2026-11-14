package vn.edu.ptit.exam.server.sync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.edu.ptit.exam.server.sync.model.ConsistencyReport;
import vn.edu.ptit.exam.server.sync.model.TableHashReport;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Test 5-Minute Consistency Checker")
class ConsistencyCheckerTest {

    @Test
    @DisplayName("Consistency check passes when all servers report matching row counts and hashes")
    void testConsistencyCheckAllMatch() {
        ConsistencyChecker checker = new ConsistencyChecker(null, "2", true, 300000L) {
            @Override
            public Map<String, TableHashReport> computeLocalTableHashes() {
                Map<String, TableHashReport> map = new HashMap<>();
                map.put("questions", new TableHashReport("questions", 50, "hash123"));
                map.put("exams", new TableHashReport("exams", 5, "hash456"));
                map.put("users", new TableHashReport("users", 25, "hash789"));
                map.put("attempts", new TableHashReport("attempts", 10, "hashabc"));
                map.put("attempt_answers", new TableHashReport("attempt_answers", 200, "hashdef"));
                return map;
            }
        };

        // All peers return the exact same counts and hashes
        checker.setPeerRequester(peerId -> {
            Map<String, TableHashReport> map = new HashMap<>();
            map.put("questions", new TableHashReport("questions", 50, "hash123"));
            map.put("exams", new TableHashReport("exams", 5, "hash456"));
            map.put("users", new TableHashReport("users", 25, "hash789"));
            map.put("attempts", new TableHashReport("attempts", 10, "hashabc"));
            map.put("attempt_answers", new TableHashReport("attempt_answers", 200, "hashdef"));
            return map;
        });

        ConsistencyReport report = checker.runCheck();
        assertNotNull(report);
        assertTrue(report.isConsistent(), "Report must be consistent when all counts and hashes match");
        assertEquals("Tất cả bảng đều nhất quán giữa các Server", report.getDetails());
    }

    @Test
    @DisplayName("Consistency check fails and logs alert when mismatch is detected")
    void testConsistencyCheckMismatchDetected() {
        ConsistencyChecker checker = new ConsistencyChecker(null, "2", true, 300000L) {
            @Override
            public Map<String, TableHashReport> computeLocalTableHashes() {
                Map<String, TableHashReport> map = new HashMap<>();
                map.put("questions", new TableHashReport("questions", 50, "hash123"));
                map.put("exams", new TableHashReport("exams", 5, "hash456"));
                map.put("users", new TableHashReport("users", 25, "hash789"));
                map.put("attempts", new TableHashReport("attempts", 10, "hashabc"));
                map.put("attempt_answers", new TableHashReport("attempt_answers", 200, "hashdef"));
                return map;
            }
        };

        // Peer 3 has 51 questions and different hash!
        checker.setPeerRequester(peerId -> {
            Map<String, TableHashReport> map = new HashMap<>();
            if ("3".equals(peerId)) {
                map.put("questions", new TableHashReport("questions", 51, "hash_different_3"));
            } else {
                map.put("questions", new TableHashReport("questions", 50, "hash123"));
            }
            map.put("exams", new TableHashReport("exams", 5, "hash456"));
            map.put("users", new TableHashReport("users", 25, "hash789"));
            map.put("attempts", new TableHashReport("attempts", 10, "hashabc"));
            map.put("attempt_answers", new TableHashReport("attempt_answers", 200, "hashdef"));
            return map;
        });

        ConsistencyReport report = checker.runCheck();
        assertNotNull(report);
        assertFalse(report.isConsistent(), "Report must be inconsistent when a mismatch exists");
        assertTrue(report.getDetails().contains("questions mismatch"), "Details must explain table mismatch");
        assertTrue(report.getDetails().contains("server 3"), "Details must reference differing server 3");
    }
}
