package vn.edu.ptit.exam.server.failover;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.edu.ptit.exam.common.dto.AnswerItemDto;
import vn.edu.ptit.exam.common.model.Attempt;
import vn.edu.ptit.exam.common.model.Question;
import vn.edu.ptit.exam.common.model.QuestionOption;
import vn.edu.ptit.exam.server.dao.AttemptDao;
import vn.edu.ptit.exam.server.dao.AuditLogDao;
import vn.edu.ptit.exam.server.dao.QuestionDao;
import vn.edu.ptit.exam.server.handler.SubmitHandler;

import java.sql.Timestamp;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class OrphanedAttemptScanTest {

    private AttemptDao mockAttemptDao;
    private QuestionDao mockQuestionDao;
    private AuditLogDao mockAuditLogDao;
    private SubmitHandler submitHandler;

    private final Map<String, Attempt> attemptStore = new HashMap<>();
    private final Map<String, Map<String, AnswerItemDto>> answerStore = new HashMap<>();
    private long now;

    @BeforeEach
    void setUp() {
        attemptStore.clear();
        answerStore.clear();
        now = System.currentTimeMillis();

        mockAttemptDao = new AttemptDao(null, null) {
            @Override
            public Attempt findAttemptById(String attemptId) {
                return attemptStore.get(attemptId);
            }

            @Override
            public boolean reassignAttempt(String attemptId, String newServerId) {
                Attempt a = attemptStore.get(attemptId);
                if (a != null) {
                    a.setServerId(newServerId);
                    a.setVersion(a.getVersion() + 1);
                    return true;
                }
                return false;
            }

            @Override
            public boolean submitAttemptIdempotent(String attemptId, double score, int correctCount, boolean isLate) {
                Attempt a = attemptStore.get(attemptId);
                if (a != null && !"SUBMITTED".equalsIgnoreCase(a.getStatus())) {
                    a.setStatus("SUBMITTED");
                    a.setScore(score);
                    a.setCorrectCount(correctCount);
                    a.setLate(isLate);
                    a.setSubmitTime(new Timestamp(System.currentTimeMillis()));
                    return true;
                }
                return false;
            }

            @Override
            public Map<String, AnswerItemDto> loadSavedAnswers(String attemptId) {
                return answerStore.getOrDefault(attemptId, Collections.emptyMap());
            }

            @Override
            public Map<String, Object> getExamInfo(String examId) {
                Map<String, Object> map = new HashMap<>();
                map.put("id", examId);
                map.put("maxScore", 10.0);
                map.put("showScoreType", "IMMEDIATE");
                map.put("totalQuestions", 1);
                return map;
            }

            @Override
            public List<Attempt> scanExpiredAttemptsForDownServers(List<String> downServerIds, long lateToleranceMs) {
                List<Attempt> result = new ArrayList<>();
                long threshold = System.currentTimeMillis() - lateToleranceMs;
                for (Attempt a : attemptStore.values()) {
                    if ("IN_PROGRESS".equalsIgnoreCase(a.getStatus())
                            && downServerIds.contains(a.getServerId())
                            && a.getDeadline().getTime() < threshold) {
                        result.add(a);
                    }
                }
                return result;
            }
        };

        mockQuestionDao = new QuestionDao(null) {
            @Override
            public Map<String, String> getCorrectAnswerMap(String examId) {
                return Map.of("q-1", "opt-1");
            }

            @Override
            public List<Question> getExamQuestions(String examId) {
                List<Question> list = new ArrayList<>();
                Question q = new Question();
                q.setId("q-1");
                q.setContent("1+1=?");
                QuestionOption opt = new QuestionOption("opt-1", "q-1", "A", "2", true, 1);
                q.setOptions(List.of(opt));
                list.add(q);
                return list;
            }
        };

        mockAuditLogDao = new AuditLogDao(null) {
            @Override
            public void log(String action, String userId, String entityType, String entityId,
                            String serverId, String ipAddress, String details) {
            }
        };

        submitHandler = new SubmitHandler(mockAttemptDao, mockQuestionDao, mockAuditLogDao, "server-2");
    }

    @Test
    @DisplayName("Scans expired attempts of DOWN server 3, reassigns to server-2, and auto-submits")
    void testScanAndReassignOrphanedAttemptsOfDownServers() throws Exception {
        // Create an attempt belonging to dead server-3 that exceeded deadline by 10s
        Attempt a1 = new Attempt();
        a1.setId("att-orphan-1");
        a1.setExamId("exam-1");
        a1.setStudentId("student-orphan");
        a1.setSeed(777L);
        a1.setStartTime(new Timestamp(now - 100000));
        a1.setDeadline(new Timestamp(now - 10000)); // expired 10s ago
        a1.setServerId("server-3");
        a1.setStatus("IN_PROGRESS");
        a1.setVersion(1);
        attemptStore.put(a1.getId(), a1);

        // Student chose the correct option before disconnecting
        answerStore.put("att-orphan-1", Map.of("q-1", new AnswerItemDto("q-1", "opt-1", false)));

        List<String> downServers = List.of("server-3");

        // Server-2 background scanner runs
        List<Attempt> orphaned = mockAttemptDao.scanExpiredAttemptsForDownServers(downServers, 5000);
        assertEquals(1, orphaned.size());

        for (Attempt a : orphaned) {
            boolean reassigned = mockAttemptDao.reassignAttempt(a.getId(), "server-2");
            assertTrue(reassigned);
            a.setServerId("server-2");
            submitHandler.autoSubmitExpiredAttempt(a);
        }

        // Verify status and score
        Attempt updated = attemptStore.get("att-orphan-1");
        assertNotNull(updated);
        assertEquals("SUBMITTED", updated.getStatus());
        assertEquals("server-2", updated.getServerId());
        assertEquals(10.0, updated.getScore(), 0.001);
        assertEquals(1, updated.getCorrectCount());
        assertTrue(updated.isLate());
    }

    @Test
    @DisplayName("Does not touch attempts belonging to live servers or unexpired attempts")
    void testDoNotTouchAttemptsOfUpServersOrUnexpired() throws Exception {
        // Attempt on server-1 (UP)
        Attempt aUp = new Attempt();
        aUp.setId("att-up");
        aUp.setExamId("exam-1");
        aUp.setStudentId("student-up");
        aUp.setStartTime(new Timestamp(now - 100000));
        aUp.setDeadline(new Timestamp(now - 10000));
        aUp.setServerId("server-1");
        aUp.setStatus("IN_PROGRESS");
        attemptStore.put(aUp.getId(), aUp);

        // Attempt on server-3 (DOWN), but deadline is still in the future
        Attempt aActive = new Attempt();
        aActive.setId("att-active");
        aActive.setExamId("exam-1");
        aActive.setStudentId("student-active");
        aActive.setStartTime(new Timestamp(now - 10000));
        aActive.setDeadline(new Timestamp(now + 60000)); // still active!
        aActive.setServerId("server-3");
        aActive.setStatus("IN_PROGRESS");
        attemptStore.put(aActive.getId(), aActive);

        List<String> downServers = List.of("server-3");
        List<Attempt> orphaned = mockAttemptDao.scanExpiredAttemptsForDownServers(downServers, 5000);

        // Neither should be matched
        assertTrue(orphaned.isEmpty());
    }
}
