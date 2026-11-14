package vn.edu.ptit.exam.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.edu.ptit.exam.common.config.Config;
import vn.edu.ptit.exam.common.dto.AnswerItemDto;
import vn.edu.ptit.exam.common.dto.AuthToken;
import vn.edu.ptit.exam.common.dto.Message;
import vn.edu.ptit.exam.common.dto.StartExamResponse;
import vn.edu.ptit.exam.common.enums.ErrorCode;
import vn.edu.ptit.exam.common.enums.MessageType;
import vn.edu.ptit.exam.common.enums.UserRole;
import vn.edu.ptit.exam.common.model.Attempt;
import vn.edu.ptit.exam.common.model.Question;
import vn.edu.ptit.exam.common.model.QuestionOption;
import vn.edu.ptit.exam.common.util.HmacUtil;
import vn.edu.ptit.exam.common.util.JsonUtil;
import vn.edu.ptit.exam.server.dao.AttemptDao;
import vn.edu.ptit.exam.server.dao.QuestionDao;
import vn.edu.ptit.exam.server.handler.StartExamHandler;
import vn.edu.ptit.exam.server.net.SocketContext;

import java.net.Socket;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StartExamHandlerTest {

    private AttemptDao mockAttemptDao;
    private QuestionDao mockQuestionDao;
    private StartExamHandler startExamHandler;
    private SocketContext dummyContext;

    private final Map<String, Attempt> attemptsByStudentExam = new HashMap<>();
    private String studentToken;
    private final String secretKey = Config.getInstance().getHmacSecretKey();
    private long now;

    @BeforeEach
    void setUp() {
        attemptsByStudentExam.clear();
        now = System.currentTimeMillis();

        AuthToken token = new AuthToken("u-sv-001", "sv_01", "Nguyen Van A", UserRole.STUDENT, now, now + 4 * 3600 * 1000);
        studentToken = HmacUtil.createToken(token, secretKey);

        mockAttemptDao = new AttemptDao(null, null) {
            @Override
            public boolean isStudentAssignedToExam(String studentId, String examId) {
                return "u-sv-001".equals(studentId) && "exam-001".equals(examId);
            }

            @Override
            public Map<String, Object> getExamInfo(String examId) {
                if ("exam-001".equals(examId)) {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", "exam-001");
                    map.put("title", "Exam LTM");
                    map.put("subject", "LTM");
                    map.put("durationMinutes", 45);
                    map.put("startTime", now - 100000);
                    map.put("endTime", now + 3600000);
                    map.put("totalQuestions", 2);
                    return map;
                }
                return null;
            }

            @Override
            public Attempt findAttemptByStudentAndExam(String studentId, String examId) {
                return attemptsByStudentExam.get(studentId + "-" + examId);
            }

            @Override
            public void createAttempt(Attempt attempt) {
                attemptsByStudentExam.put(attempt.getStudentId() + "-" + attempt.getExamId(), attempt);
            }

            @Override
            public Map<String, AnswerItemDto> loadSavedAnswers(String attemptId) {
                Map<String, AnswerItemDto> map = new HashMap<>();
                map.put("q-001", new AnswerItemDto("q-001", "opt-001-B", false));
                return map;
            }
        };

        mockQuestionDao = new QuestionDao(null) {
            @Override
            public List<Question> getExamQuestions(String examId) {
                List<Question> list = new ArrayList<>();
                Question q1 = new Question("q-001", "LTM", 1, "EASY", "Content 1", false, 1);
                q1.setOptions(List.of(new QuestionOption("opt-001-A", "q-001", "A", "A", false, 1),
                                      new QuestionOption("opt-001-B", "q-001", "B", "B", true, 2)));
                list.add(q1);
                return list;
            }
        };

        startExamHandler = new StartExamHandler(mockAttemptDao, mockQuestionDao, "SERVER_1");
        dummyContext = new SocketContext("test-start-ctx", new Socket());
    }

    @Test
    @DisplayName("Should return 403 NOT_ASSIGNED when student is not assigned to exam")
    void testNotAssigned() throws Exception {
        Message req = Message.request(MessageType.START_EXAM, "req-start-not-assigned");
        req.setToken(studentToken);
        req.setPayload(Map.of("examId", "exam-999")); // student not assigned to exam-999

        Message resp = startExamHandler.handle(req, dummyContext);
        assertEquals(ErrorCode.NOT_ASSIGNED.getCode(), resp.getCode());
    }

    @Test
    @DisplayName("Should start new exam: creates attempt, signs ticket, and returns questions")
    void testStartNewExam() throws Exception {
        Message req = Message.request(MessageType.START_EXAM, "req-start-ok");
        req.setToken(studentToken);
        req.setPayload(Map.of("examId", "exam-001"));

        Message resp = startExamHandler.handle(req, dummyContext);
        assertEquals("OK", resp.getStatus());
        assertNotNull(resp.getPayload());

        StartExamResponse startResp = JsonUtil.convertValue(resp.getPayload(), StartExamResponse.class);
        assertNotNull(startResp.getAttemptId());
        assertNotNull(startResp.getTicket());
        assertFalse(startResp.isRecovered());

        // Verify signed ExamTicket
        assertTrue(HmacUtil.verifyExamTicket(startResp.getTicket(), secretKey));
        assertEquals(1, startResp.getQuestions().size());
    }

    @Test
    @DisplayName("Should recover ongoing exam if student already has an IN_PROGRESS attempt")
    void testRecoverOngoingExam() throws Exception {
        // Pre-create attempt
        Attempt existing = new Attempt();
        existing.setId("att-recovered-001");
        existing.setExamId("exam-001");
        existing.setStudentId("u-sv-001");
        existing.setSeed(55555L);
        existing.setStartTime(new Timestamp(now - 10000));
        existing.setDeadline(new Timestamp(now + 50000));
        existing.setStatus("IN_PROGRESS");
        existing.setServerId("SERVER_1");
        attemptsByStudentExam.put("u-sv-001-exam-001", existing);

        Message req = Message.request(MessageType.START_EXAM, "req-start-recover");
        req.setToken(studentToken);
        req.setPayload(Map.of("examId", "exam-001"));

        Message resp = startExamHandler.handle(req, dummyContext);
        assertEquals("OK", resp.getStatus());

        StartExamResponse startResp = JsonUtil.convertValue(resp.getPayload(), StartExamResponse.class);
        assertEquals("att-recovered-001", startResp.getAttemptId());
        assertTrue(startResp.isRecovered());

        // Check that previously saved answer was restored
        assertEquals("opt-001-B", startResp.getQuestions().get(0).getSelectedOptionId());
    }
}
