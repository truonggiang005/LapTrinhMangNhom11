package vn.edu.ptit.exam.server.failover;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.edu.ptit.exam.common.config.Config;
import vn.edu.ptit.exam.common.dto.AnswerItemDto;
import vn.edu.ptit.exam.common.dto.AuthToken;
import vn.edu.ptit.exam.common.dto.Message;
import vn.edu.ptit.exam.common.dto.SubmitResultDto;
import vn.edu.ptit.exam.common.enums.MessageType;
import vn.edu.ptit.exam.common.enums.UserRole;
import vn.edu.ptit.exam.common.model.Attempt;
import vn.edu.ptit.exam.common.model.Question;
import vn.edu.ptit.exam.common.model.QuestionOption;
import vn.edu.ptit.exam.common.util.HmacUtil;
import vn.edu.ptit.exam.common.util.JsonUtil;
import vn.edu.ptit.exam.server.dao.AttemptDao;
import vn.edu.ptit.exam.server.dao.AuditLogDao;
import vn.edu.ptit.exam.server.dao.QuestionDao;
import vn.edu.ptit.exam.server.handler.SubmitHandler;
import vn.edu.ptit.exam.server.net.SocketContext;

import java.sql.Timestamp;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SubmitIdempotentOnFailoverTest {

    private AttemptDao mockAttemptDao;
    private QuestionDao mockQuestionDao;
    private AuditLogDao mockAuditLogDao;
    private SubmitHandler submitHandlerServer2;
    private SocketContext dummyContext;

    private final Map<String, Attempt> attemptStore = new HashMap<>();
    private final Map<String, Map<String, AnswerItemDto>> answerStore = new HashMap<>();
    private final String secretKey = Config.getInstance().getHmacSecretKey();
    private String studentToken;
    private long now;

    @BeforeEach
    void setUp() {
        attemptStore.clear();
        answerStore.clear();
        now = System.currentTimeMillis();

        AuthToken token = new AuthToken("u-sv-001", "sv_01", "Nguyen Van A", UserRole.STUDENT, now, now + 4 * 3600 * 1000);
        studentToken = HmacUtil.createToken(token, secretKey);

        mockAttemptDao = new AttemptDao(null, null) {
            @Override
            public Attempt findAttemptById(String attemptId) {
                return attemptStore.get(attemptId);
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
                map.put("totalQuestions", 2);
                return map;
            }

            @Override
            public String submitAttemptAndGetOpId(String attemptId, double score, int correctCount, boolean isLate) {
                boolean updated = submitAttemptIdempotent(attemptId, score, correctCount, isLate);
                return updated ? "op-test-" + attemptId : null;
            }

            @Override
            public boolean reassignAttempt(String attemptId, String newServerId) {
                Attempt a = attemptStore.get(attemptId);
                if (a != null) {
                    a.setServerId(newServerId);
                    return true;
                }
                return false;
            }
        };

        mockQuestionDao = new QuestionDao(null) {
            @Override
            public Map<String, String> getCorrectAnswerMap(String examId) {
                Map<String, String> map = new HashMap<>();
                map.put("q-1", "opt-1a");
                map.put("q-2", "opt-2a");
                return map;
            }

            @Override
            public List<Question> getExamQuestions(String examId) {
                List<Question> list = new ArrayList<>();
                Question q1 = new Question();
                q1.setId("q-1");
                q1.setContent("Cau 1?");
                q1.setOptions(List.of(
                        new QuestionOption("opt-1a", "q-1", "A", "True", true, 1),
                        new QuestionOption("opt-1b", "q-1", "B", "False", false, 2)
                ));

                Question q2 = new Question();
                q2.setId("q-2");
                q2.setContent("Cau 2?");
                q2.setOptions(List.of(
                        new QuestionOption("opt-2a", "q-2", "A", "Yes", true, 1),
                        new QuestionOption("opt-2b", "q-2", "B", "No", false, 2)
                ));
                list.add(q1);
                list.add(q2);
                return list;
            }
        };

        mockAuditLogDao = new AuditLogDao(null) {
            @Override
            public void log(String action, String userId, String entityType, String entityId,
                            String serverId, String ipAddress, String details) {
            }
        };

        submitHandlerServer2 = new SubmitHandler(mockAttemptDao, mockQuestionDao, mockAuditLogDao, "server-2");
        dummyContext = new SocketContext("conn-1", new java.net.Socket());
    }

    @Test
    @DisplayName("If previous server died after scoring, resubmission with SUBMIT-<attemptId> returns identical cached score")
    void testResubmissionReturnsIdenticalScoreWithoutRescoring() throws Exception {
        // Attempt already SUBMITTED by server-3 before it died
        Attempt attempt = new Attempt();
        attempt.setId("att-failover-1");
        attempt.setExamId("exam-1");
        attempt.setStudentId("u-sv-001");
        attempt.setSeed(9999L);
        attempt.setStartTime(new Timestamp(now - 20000));
        attempt.setDeadline(new Timestamp(now + 40000));
        attempt.setSubmitTime(new Timestamp(now - 5000));
        attempt.setServerId("server-3");
        attempt.setStatus("SUBMITTED");
        attempt.setScore(10.0);
        attempt.setCorrectCount(2);
        attempt.setTotalQuestions(2);
        attemptStore.put(attempt.getId(), attempt);

        // Client resends fixed requestId SUBMIT-att-failover-1 to Server 2
        Message submitReq = Message.request(MessageType.SUBMIT, "SUBMIT-att-failover-1");
        submitReq.setToken(studentToken);
        submitReq.setAttemptId("att-failover-1");

        Message resp = submitHandlerServer2.handle(submitReq, dummyContext);

        assertNotNull(resp);
        assertTrue(resp.isOk());
        assertEquals("SUBMIT-att-failover-1", resp.getRequestId());

        SubmitResultDto result = JsonUtil.fromJson(JsonUtil.toJson(resp.getPayload()), SubmitResultDto.class);
        assertEquals(10.0, result.getScore(), 0.001);
        assertEquals(2, result.getCorrectCount());
        assertEquals(2, result.getTotalQuestions());
        assertEquals("SUBMITTED", result.getStatus());
    }

    @Test
    @DisplayName("If replication had not reached Server 2 when Server 3 died, scoring on Server 2 is deterministically identical")
    void testRescoringOnReplacementServerIsDeterministic() throws Exception {
        // Attempt was still IN_PROGRESS in Server 2's DB
        Attempt attempt = new Attempt();
        attempt.setId("att-failover-2");
        attempt.setExamId("exam-1");
        attempt.setStudentId("u-sv-001");
        attempt.setSeed(9999L);
        attempt.setStartTime(new Timestamp(now - 20000));
        attempt.setDeadline(new Timestamp(now + 40000));
        attempt.setServerId("server-2");
        attempt.setStatus("IN_PROGRESS");
        attemptStore.put(attempt.getId(), attempt);

        // Student chose opt-1a (correct) and opt-2b (wrong)
        Map<String, AnswerItemDto> answers = new HashMap<>();
        answers.put("q-1", new AnswerItemDto("q-1", "opt-1a", false));
        answers.put("q-2", new AnswerItemDto("q-2", "opt-2b", false));
        answerStore.put("att-failover-2", answers);

        Message submitReq = Message.request(MessageType.SUBMIT, "SUBMIT-att-failover-2");
        submitReq.setToken(studentToken);
        submitReq.setAttemptId("att-failover-2");

        // First submit on Server 2
        Message resp1 = submitHandlerServer2.handle(submitReq, dummyContext);
        assertTrue(resp1.isOk());
        SubmitResultDto res1 = JsonUtil.fromJson(JsonUtil.toJson(resp1.getPayload()), SubmitResultDto.class);
        assertEquals(5.0, res1.getScore(), 0.001); // 1 out of 2 correct = 5.0 / 10
        assertEquals(1, res1.getCorrectCount());

        // Repeated submit on Server 2 with same fixed requestId
        Message resp2 = submitHandlerServer2.handle(submitReq, dummyContext);
        assertTrue(resp2.isOk());
        SubmitResultDto res2 = JsonUtil.fromJson(JsonUtil.toJson(resp2.getPayload()), SubmitResultDto.class);
        assertEquals(5.0, res2.getScore(), 0.001);
        assertEquals(1, res2.getCorrectCount());
        assertEquals(res1.getSubmittedAt(), res2.getSubmittedAt());
    }
}
