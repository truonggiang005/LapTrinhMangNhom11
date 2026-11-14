package vn.edu.ptit.exam.server.handler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.edu.ptit.exam.common.config.Config;
import vn.edu.ptit.exam.common.dto.*;
import vn.edu.ptit.exam.common.enums.ErrorCode;
import vn.edu.ptit.exam.common.enums.MessageType;
import vn.edu.ptit.exam.common.enums.UserRole;
import vn.edu.ptit.exam.common.util.HmacUtil;
import vn.edu.ptit.exam.common.util.JsonUtil;
import vn.edu.ptit.exam.server.dao.ExamDao;
import vn.edu.ptit.exam.server.dao.QuestionDao;
import vn.edu.ptit.exam.server.exception.ExamServerException;
import vn.edu.ptit.exam.server.sync.SyncLogWriter;
import vn.edu.ptit.exam.server.sync.SyncPeerStateDao;
import vn.edu.ptit.exam.server.sync.model.SyncPeerStatus;

import java.sql.SQLException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class ExamCreationValidationTest {

    private ExamDao inMemoryExamDao;
    private QuestionDao inMemoryQuestionDao;
    private ExamAdminHandler examAdminHandler;
    private final String hmacKey = Config.getInstance().getHmacSecretKey();
    private String lecturerToken;

    // Simulated DB stores
    private final Map<String, Integer> questionBankCount = new HashMap<>(); // "subject:chapter:difficulty" -> count
    private final Set<String> validStudentIds = new HashSet<>();
    private final Map<String, ExamDto> createdExams = new HashMap<>();

    @BeforeEach
    void setUp() {
        questionBankCount.clear();
        validStudentIds.clear();
        createdExams.clear();

        long now = System.currentTimeMillis();
        AuthToken lect = new AuthToken("u-lect-2", "gv_tuan", "Tran Anh Tuan", UserRole.LECTURER, now, now + 14400000L);
        lecturerToken = HmacUtil.createToken(lect, hmacKey);

        validStudentIds.add("sv-01");
        validStudentIds.add("sv-02");
        validStudentIds.add("sv-03");

        // Question bank: 5 easy questions in chapter 1, 2 medium in chapter 2
        questionBankCount.put("LTM:1:EASY", 5);
        questionBankCount.put("LTM:2:MEDIUM", 2);

        inMemoryQuestionDao = new QuestionDao(null) {
            @Override
            public int countAvailableQuestions(String subject, Integer chapter, String difficulty) {
                String key = subject + ":" + chapter + ":" + difficulty;
                return questionBankCount.getOrDefault(key, 0);
            }

            @Override
            public List<String> findRandomQuestionIds(String subject, Integer chapter, String difficulty, int count) {
                List<String> list = new ArrayList<>();
                for (int i = 1; i <= count; i++) {
                    list.add("q-" + subject + "-" + chapter + "-" + i);
                }
                return list;
            }
        };

        inMemoryExamDao = new ExamDao(null) {
            @Override
            public ExamDto createExamPackage(CreateExamRequest req, String createdBy, SyncLogWriter syncLogWriter, QuestionDao qDao)
                    throws ExamServerException, SQLException {
                if (req.getStartTime() >= req.getEndTime()) {
                    throw new ExamServerException(ErrorCode.BAD_REQUEST, "Giờ đóng ca thi phải sau giờ mở ca thi");
                }
                if (req.getDurationMinutes() * 60000L > (req.getEndTime() - req.getStartTime())) {
                    throw new ExamServerException(ErrorCode.BAD_REQUEST, "Thời lượng làm bài không được vượt quá khoảng thời gian mở ca thi");
                }

                if ("RANDOM_STRUCTURE".equalsIgnoreCase(req.getExamType())) {
                    for (CreateExamRequest.StructureRule rule : req.getStructureRules()) {
                        int available = qDao.countAvailableQuestions(req.getSubject(), rule.getChapter(), rule.getDifficulty());
                        if (available < rule.getCount()) {
                            throw new ExamServerException(ErrorCode.BAD_REQUEST,
                                    String.format("Ngân hàng không đủ câu hỏi cho cấu trúc đề: chương %d, độ khó %s (có %d, cần %d)",
                                            rule.getChapter(), rule.getDifficulty(), available, rule.getCount()));
                        }
                    }
                }

                for (String sId : req.getAssignedStudentIds()) {
                    if (!validStudentIds.contains(sId)) {
                        throw new ExamServerException(ErrorCode.BAD_REQUEST, "Sinh viên không tồn tại trong hệ thống: " + sId);
                    }
                }

                ExamDto exam = new ExamDto(
                        req.getId() != null ? req.getId() : UUID.randomUUID().toString(),
                        req.getTitle(), req.getSubject(), req.getStartTime(), req.getEndTime(),
                        req.getDurationMinutes(), 10, "OPEN"
                );
                createdExams.put(exam.getId(), exam);
                return exam;
            }

            @Override
            public ExamReadinessDto getExamReadiness(String examId, SyncPeerStateDao syncPeerStateDao, String localServerId) {
                int replicated = 4;
                return new ExamReadinessDto(examId, replicated, 4, true, "Sẵn sàng 4/4 Server");
            }
        };

        examAdminHandler = new ExamAdminHandler(inMemoryExamDao, inMemoryQuestionDao, null, null, "server-1");
    }

    @Test
    @DisplayName("Validation: Insufficient questions in bank for structure rules returns 400 BAD_REQUEST")
    void testInsufficientQuestionsFails() throws Exception {
        CreateExamRequest req = new CreateExamRequest();
        req.setTitle("Kiểm tra 15 phút LTM");
        req.setSubject("LTM");
        long start = System.currentTimeMillis() + 3600000L;
        req.setStartTime(start);
        req.setEndTime(start + 7200000L);
        req.setDurationMinutes(45);
        req.setExamType("RANDOM_STRUCTURE");
        req.setAssignedStudentIds(Arrays.asList("sv-01", "sv-02"));

        // Rule asks for 10 easy questions, but only 5 available
        req.setStructureRules(Collections.singletonList(
                new CreateExamRequest.StructureRule(1, "EASY", 10)
        ));

        Message msg = Message.request(MessageType.EXAM_CREATE, "req-create-fail");
        msg.setToken(lecturerToken);
        msg.setPayload(req);

        Message resp = examAdminHandler.handle(msg, null);
        assertEquals("ERROR", resp.getStatus());
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), resp.getCode());
        assertTrue(resp.getMessage().contains("Ngân hàng không đủ câu hỏi"));
    }

    @Test
    @DisplayName("Success: Sufficient questions creates exam package")
    void testSufficientQuestionsCreatesExam() throws Exception {
        CreateExamRequest req = new CreateExamRequest();
        req.setTitle("Thi cuối kỳ LTM");
        req.setSubject("LTM");
        long start = System.currentTimeMillis() + 3600000L;
        req.setStartTime(start);
        req.setEndTime(start + 7200000L);
        req.setDurationMinutes(45);
        req.setExamType("RANDOM_STRUCTURE");
        req.setAssignedStudentIds(Arrays.asList("sv-01", "sv-02"));

        // Rule asks for 3 easy (5 available) and 1 medium (2 available)
        req.setStructureRules(Arrays.asList(
                new CreateExamRequest.StructureRule(1, "EASY", 3),
                new CreateExamRequest.StructureRule(2, "MEDIUM", 1)
        ));

        Message msg = Message.request(MessageType.EXAM_CREATE, "req-create-ok");
        msg.setToken(lecturerToken);
        msg.setPayload(req);

        Message resp = examAdminHandler.handle(msg, null);
        assertEquals("OK", resp.getStatus());
        assertEquals(200, resp.getCode());

        ExamDto created = JsonUtil.convertValue(resp.getPayload(), ExamDto.class);
        assertNotNull(created.getId());
        assertEquals("Thi cuối kỳ LTM", created.getTitle());
    }

    @Test
    @DisplayName("Validation: Invalid time window returns 400 BAD_REQUEST")
    void testInvalidTimeWindow() throws Exception {
        CreateExamRequest req = new CreateExamRequest();
        req.setTitle("Lỗi giờ thi");
        req.setSubject("LTM");
        long start = System.currentTimeMillis() + 3600000L;
        req.setStartTime(start);
        req.setEndTime(start - 1000L); // endTime before startTime
        req.setDurationMinutes(45);
        req.setAssignedStudentIds(Collections.singletonList("sv-01"));

        Message msg = Message.request(MessageType.EXAM_CREATE, "req-time-fail");
        msg.setToken(lecturerToken);
        msg.setPayload(req);

        Message resp = examAdminHandler.handle(msg, null);
        assertEquals("ERROR", resp.getStatus());
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), resp.getCode());
    }

    @Test
    @DisplayName("Validation: Non-existent assigned student returns 400 BAD_REQUEST")
    void testNonExistentStudentFails() throws Exception {
        CreateExamRequest req = new CreateExamRequest();
        req.setTitle("Thi thử");
        req.setSubject("LTM");
        long start = System.currentTimeMillis() + 3600000L;
        req.setStartTime(start);
        req.setEndTime(start + 7200000L);
        req.setDurationMinutes(45);
        req.setExamType("RANDOM_STRUCTURE");
        req.setStructureRules(Collections.singletonList(new CreateExamRequest.StructureRule(1, "EASY", 1)));
        req.setAssignedStudentIds(Collections.singletonList("sv-UNKNOWN-999"));

        Message msg = Message.request(MessageType.EXAM_CREATE, "req-student-fail");
        msg.setToken(lecturerToken);
        msg.setPayload(req);

        Message resp = examAdminHandler.handle(msg, null);
        assertEquals("ERROR", resp.getStatus());
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), resp.getCode());
        assertTrue(resp.getMessage().contains("Sinh viên không tồn tại"));
    }

    @Test
    @DisplayName("Readiness check: returns 4/4 replicated status")
    void testExamReadiness() throws Exception {
        Message req = Message.request(MessageType.EXAM_MONITOR, "req-readiness");
        req.setToken(lecturerToken);
        Map<String, Object> payload = new HashMap<>();
        payload.put("examId", "exam-100");
        payload.put("checkReadiness", true);
        req.setPayload(payload);

        Message resp = examAdminHandler.handle(req, null);
        assertEquals("OK", resp.getStatus());
        ExamReadinessDto readiness = JsonUtil.convertValue(resp.getPayload(), ExamReadinessDto.class);
        assertEquals(4, readiness.getReplicatedServers());
        assertEquals(4, readiness.getTotalServers());
        assertTrue(readiness.isReady());
    }
}
