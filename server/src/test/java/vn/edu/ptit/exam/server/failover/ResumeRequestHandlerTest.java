package vn.edu.ptit.exam.server.failover;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.edu.ptit.exam.common.config.Config;
import vn.edu.ptit.exam.common.dto.*;
import vn.edu.ptit.exam.common.enums.ErrorCode;
import vn.edu.ptit.exam.common.enums.MessageType;
import vn.edu.ptit.exam.common.enums.UserRole;
import vn.edu.ptit.exam.common.model.Attempt;
import vn.edu.ptit.exam.common.model.Question;
import vn.edu.ptit.exam.common.util.HmacUtil;
import vn.edu.ptit.exam.common.util.JsonUtil;
import vn.edu.ptit.exam.server.dao.AttemptDao;
import vn.edu.ptit.exam.server.dao.QuestionDao;
import vn.edu.ptit.exam.server.handler.ResumeRequestHandler;
import vn.edu.ptit.exam.server.net.SocketContext;

import java.sql.Timestamp;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ResumeRequestHandlerTest {

    private AttemptDao mockAttemptDao;
    private QuestionDao mockQuestionDao;
    private ResumeRequestHandler resumeHandler;
    private SocketContext dummyContext;

    private final Map<String, Attempt> attemptStore = new HashMap<>();
    private final Map<String, Map<String, AnswerItemDto>> answersStore = new HashMap<>();
    private final String secretKey = Config.getInstance().getHmacSecretKey();
    private String studentToken;
    private long now;

    @BeforeEach
    void setUp() {
        attemptStore.clear();
        answersStore.clear();
        now = System.currentTimeMillis();

        AuthToken token = new AuthToken("u-sv-001", "sv_01", "Nguyen Van A", UserRole.STUDENT, now, now + 4 * 3600 * 1000);
        studentToken = HmacUtil.createToken(token, secretKey);

        mockAttemptDao = new AttemptDao(null, null) {
            @Override
            public Attempt findAttemptById(String attemptId) {
                return attemptStore.get(attemptId);
            }

            @Override
            public void createAttempt(Attempt attempt) {
                attemptStore.put(attempt.getId(), attempt);
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
            public Map<String, AnswerItemDto> loadSavedAnswers(String attemptId) {
                return answersStore.getOrDefault(attemptId, Collections.emptyMap());
            }
        };

        mockQuestionDao = new QuestionDao(null) {
            @Override
            public List<Question> getExamQuestions(String examId) {
                return Collections.emptyList();
            }
        };

        // Replacement server is server-2
        resumeHandler = new ResumeRequestHandler(mockAttemptDao, mockQuestionDao, "server-2");
        dummyContext = new SocketContext("conn-1", new java.net.Socket());
    }

    @Test
    @DisplayName("RESUME_REQUEST with existing IN_PROGRESS attempt reassigns ownership to server-2 and returns RESUME_OK")
    void testResumeExistingInProgressAttempt_ReassignsAndReturnsOk() throws Exception {
        Attempt attempt = new Attempt();
        attempt.setId("att-101");
        attempt.setExamId("exam-01");
        attempt.setStudentId("u-sv-001");
        attempt.setSeed(999L);
        attempt.setStartTime(new Timestamp(now - 10000));
        attempt.setDeadline(new Timestamp(now + 60000));
        attempt.setServerId("server-1");
        attempt.setStatus("IN_PROGRESS");
        attempt.setVersion(1);
        attemptStore.put(attempt.getId(), attempt);

        Map<String, AnswerItemDto> saved = new HashMap<>();
        saved.put("q-1", new AnswerItemDto("q-1", "opt-1", false));
        answersStore.put("att-101", saved);

        Message req = Message.request(MessageType.RESUME_REQUEST, "REQ-01");
        req.setToken(studentToken);
        req.setAttemptId("att-101");

        Message resp = resumeHandler.handle(req, dummyContext);

        assertNotNull(resp);
        assertTrue(resp.isOk());
        assertEquals("att-101", resp.getAttemptId());
        assertEquals(MessageType.RESUME_OK.name(), resp.getType());

        // Verify attempt reassigned to server-2
        assertEquals("server-2", attempt.getServerId());
        assertEquals(2, attempt.getVersion());

        // Check payload
        Map<?, ?> payload = (Map<?, ?>) resp.getPayload();
        assertEquals("IN_PROGRESS", payload.get("status"));
        assertEquals("server-2", payload.get("serverId"));
        assertTrue(((Number) payload.get("remainingSeconds")).longValue() > 0);
        List<?> answers = (List<?>) payload.get("answers");
        assertEquals(1, answers.size());
    }

    @Test
    @DisplayName("RESUME_REQUEST reconstructs attempt from verified ExamTicket if attempt missing in local DB")
    void testResumeMissingAttemptReconstructedFromValidTicket() throws Exception {
        // Attempt does not exist in local attemptStore
        long deadline = now + 45 * 60 * 1000;
        ExamTicket ticket = new ExamTicket("att-999", "u-sv-001", "exam-01", 12345678L, now, deadline, "server-1");
        HmacUtil.signExamTicket(ticket, secretKey);

        Message req = Message.request(MessageType.RESUME_REQUEST, "REQ-02");
        req.setToken(studentToken);
        req.setAttemptId("att-999");
        req.setPayload(Map.of("ticket", ticket));

        Message resp = resumeHandler.handle(req, dummyContext);

        assertNotNull(resp);
        assertTrue(resp.isOk());
        assertEquals(MessageType.RESUME_OK.name(), resp.getType());

        // Check that attempt was reconstructed and stored
        Attempt reconstructed = attemptStore.get("att-999");
        assertNotNull(reconstructed);
        assertEquals("att-999", reconstructed.getId());
        assertEquals("u-sv-001", reconstructed.getStudentId());
        assertEquals(12345678L, reconstructed.getSeed());
        assertEquals("server-2", reconstructed.getServerId());
        assertEquals("IN_PROGRESS", reconstructed.getStatus());
        assertEquals(deadline, reconstructed.getDeadline().getTime());
    }

    @Test
    @DisplayName("RESUME_REQUEST fails if attempt missing and ExamTicket is invalid/tampered")
    void testResumeMissingAttemptWithInvalidTicket_Fails() throws Exception {
        ExamTicket ticket = new ExamTicket("att-fake", "u-sv-001", "exam-01", 1234L, now, now + 10000, "server-1");
        ticket.setSignature("TAMPERED_SIGNATURE");

        Message req = Message.request(MessageType.RESUME_REQUEST, "REQ-03");
        req.setToken(studentToken);
        req.setAttemptId("att-fake");
        req.setPayload(Map.of("ticket", ticket));

        Message resp = resumeHandler.handle(req, dummyContext);

        assertNotNull(resp);
        assertTrue(resp.isError());
        assertEquals(ErrorCode.NOT_FOUND.getCode(), resp.getCode());
    }

    @Test
    @DisplayName("RESUME_REQUEST for already SUBMITTED attempt returns previous result deterministically without re-scoring")
    void testResumeAlreadySubmittedAttempt_ReturnsPreviousResultWithoutRescoring() throws Exception {
        Attempt attempt = new Attempt();
        attempt.setId("att-done");
        attempt.setExamId("exam-01");
        attempt.setStudentId("u-sv-001");
        attempt.setSeed(4321L);
        attempt.setStartTime(new Timestamp(now - 30000));
        attempt.setDeadline(new Timestamp(now - 5000));
        attempt.setSubmitTime(new Timestamp(now - 6000));
        attempt.setServerId("server-1");
        attempt.setStatus("SUBMITTED");
        attempt.setScore(9.5);
        attempt.setCorrectCount(19);
        attempt.setTotalQuestions(20);
        attemptStore.put(attempt.getId(), attempt);

        Message req = Message.request(MessageType.RESUME_REQUEST, "REQ-04");
        req.setToken(studentToken);
        req.setAttemptId("att-done");

        Message resp = resumeHandler.handle(req, dummyContext);

        assertNotNull(resp);
        assertTrue(resp.isOk());
        assertEquals(MessageType.RESUME_OK.name(), resp.getType());

        Map<?, ?> payload = (Map<?, ?>) resp.getPayload();
        assertEquals("SUBMITTED", payload.get("status"));
        assertEquals(9.5, (Double) payload.get("score"), 0.001);
        assertEquals(19, payload.get("correctCount"));
        assertEquals(20, payload.get("totalQuestions"));
        assertEquals(0, payload.get("remainingSeconds"));
    }

    @Test
    @DisplayName("RESUME_REQUEST fails with 403 FORBIDDEN if token user does not own the attempt")
    void testResumeAttemptWrongUser_ReturnsForbidden() throws Exception {
        Attempt attempt = new Attempt();
        attempt.setId("att-other");
        attempt.setExamId("exam-01");
        attempt.setStudentId("u-sv-999"); // belongs to someone else
        attempt.setStatus("IN_PROGRESS");
        attemptStore.put(attempt.getId(), attempt);

        Message req = Message.request(MessageType.RESUME_REQUEST, "REQ-05");
        req.setToken(studentToken); // u-sv-001
        req.setAttemptId("att-other");

        Message resp = resumeHandler.handle(req, dummyContext);

        assertNotNull(resp);
        assertTrue(resp.isError());
        assertEquals(ErrorCode.FORBIDDEN.getCode(), resp.getCode());
    }
}
