package vn.edu.ptit.exam.server;

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
import vn.edu.ptit.exam.common.util.HmacUtil;
import vn.edu.ptit.exam.common.util.JsonUtil;
import vn.edu.ptit.exam.server.dao.AttemptDao;
import vn.edu.ptit.exam.server.dao.AuditLogDao;
import vn.edu.ptit.exam.server.dao.QuestionDao;
import vn.edu.ptit.exam.server.handler.SubmitHandler;
import vn.edu.ptit.exam.server.net.SocketContext;

import java.net.Socket;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SubmitHandlerTest {

    private AttemptDao mockAttemptDao;
    private QuestionDao mockQuestionDao;
    private AuditLogDao mockAuditLogDao;
    private SubmitHandler submitHandler;
    private SocketContext dummyContext;

    private final Map<String, Attempt> attemptStore = new HashMap<>();
    private final Map<String, AnswerItemDto> studentAnswersStore = new HashMap<>();
    private String studentToken;
    private final String secretKey = Config.getInstance().getHmacSecretKey();

    @BeforeEach
    void setUp() {
        attemptStore.clear();
        studentAnswersStore.clear();

        long now = System.currentTimeMillis();
        AuthToken token = new AuthToken("u-sv-001", "sv_01", "Nguyen Van A", UserRole.STUDENT, now, now + 4 * 3600 * 1000);
        studentToken = HmacUtil.createToken(token, secretKey);

        Attempt attempt = new Attempt();
        attempt.setId("att-001");
        attempt.setExamId("exam-001");
        attempt.setStudentId("u-sv-001");
        attempt.setSeed(12345L);
        attempt.setStartTime(new Timestamp(now - 10000));
        attempt.setDeadline(new Timestamp(now + 30000)); // still active
        attempt.setServerId("SERVER_1");
        attempt.setStatus("IN_PROGRESS");
        attempt.setTotalQuestions(2);
        attemptStore.put("att-001", attempt);

        studentAnswersStore.put("q-001", new AnswerItemDto("q-001", "opt-001-B", false)); // correct
        studentAnswersStore.put("q-002", new AnswerItemDto("q-002", "opt-002-A", false)); // correct

        mockAttemptDao = new AttemptDao(null, null) {
            @Override
            public Attempt findAttemptById(String attemptId) {
                return attemptStore.get(attemptId);
            }

            @Override
            public Map<String, AnswerItemDto> loadSavedAnswers(String attemptId) {
                return new HashMap<>(studentAnswersStore);
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
            public boolean submitAttemptIdempotent(String attemptId, double score, int correctCount, boolean isLate) {
                Attempt a = attemptStore.get(attemptId);
                if (a == null || "SUBMITTED".equalsIgnoreCase(a.getStatus())) {
                    return false;
                }
                a.setStatus("SUBMITTED");
                a.setScore(score);
                a.setCorrectCount(correctCount);
                a.setLate(isLate);
                a.setSubmitTime(new Timestamp(System.currentTimeMillis()));
                return true;
            }
        };

        mockQuestionDao = new QuestionDao(null) {
            @Override
            public Map<String, String> getCorrectAnswerMap(String examId) {
                Map<String, String> key = new HashMap<>();
                key.put("q-001", "opt-001-B");
                key.put("q-002", "opt-002-A");
                return key;
            }
        };

        mockAuditLogDao = new AuditLogDao(null) {
            @Override
            public void log(String action, String userId, String entityType, String entityId, String serverId, String ipAddress, String details) {
            }
        };

        submitHandler = new SubmitHandler(mockAttemptDao, mockQuestionDao, mockAuditLogDao, "SERVER_1");
        dummyContext = new SocketContext("test-submit-ctx", new Socket());
    }

    @Test
    @DisplayName("Invariant 4: Submitting twice returns the exact same result (Idempotent)")
    void testIdempotentDoubleSubmit() throws Exception {
        Message req1 = new Message(MessageType.SUBMIT.name(), "SUBMIT-att-001");
        req1.setToken(studentToken);
        req1.setAttemptId("att-001");

        // First submit
        Message resp1 = submitHandler.handle(req1, dummyContext);
        assertEquals("OK", resp1.getStatus());
        SubmitResultDto res1 = JsonUtil.convertValue(resp1.getPayload(), SubmitResultDto.class);
        assertEquals(10.00, res1.getScore());
        assertEquals(2, res1.getCorrectCount());
        assertFalse(res1.isLate());

        // Second submit with the exact same SUBMIT message
        Message req2 = new Message(MessageType.SUBMIT.name(), "SUBMIT-att-001");
        req2.setToken(studentToken);
        req2.setAttemptId("att-001");

        Message resp2 = submitHandler.handle(req2, dummyContext);
        assertEquals("OK", resp2.getStatus());
        SubmitResultDto res2 = JsonUtil.convertValue(resp2.getPayload(), SubmitResultDto.class);

        // Result must match exactly
        assertEquals(res1.getScore(), res2.getScore());
        assertEquals(res1.getCorrectCount(), res2.getCorrectCount());
        assertEquals("SUBMITTED", res2.getStatus());
    }

    @Test
    @DisplayName("Submitting past deadline + tolerance should be marked as isLate = true")
    void testLateSubmission() throws Exception {
        // Move deadline to 10 seconds ago
        Attempt a = attemptStore.get("att-001");
        a.setDeadline(new Timestamp(System.currentTimeMillis() - 10000));

        Message req = new Message(MessageType.SUBMIT.name(), "SUBMIT-att-001");
        req.setToken(studentToken);
        req.setAttemptId("att-001");

        Message resp = submitHandler.handle(req, dummyContext);
        assertEquals("OK", resp.getStatus());
        SubmitResultDto res = JsonUtil.convertValue(resp.getPayload(), SubmitResultDto.class);
        assertTrue(res.isLate(), "Should be flagged as late submission");
    }
}
