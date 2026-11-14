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
import vn.edu.ptit.exam.server.dao.QuestionDao;
import vn.edu.ptit.exam.server.exception.ExamServerException;
import vn.edu.ptit.exam.server.exception.ResourceLockedException;
import vn.edu.ptit.exam.server.exception.VersionConflictException;
import vn.edu.ptit.exam.server.net.SocketContext;
import vn.edu.ptit.exam.server.sync.SyncLogWriter;

import java.sql.SQLException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class QuestionConcurrencyTest {

    private QuestionDao inMemoryQuestionDao;
    private QuestionHandler questionHandler;
    private final String hmacKey = Config.getInstance().getHmacSecretKey();

    private String lecturerToken;
    private String studentToken;

    // In-memory question table
    private final Map<String, QuestionDto> questionsDb = new HashMap<>();
    private final Map<String, Boolean> activeExamQuestionMap = new HashMap<>();

    @BeforeEach
    void setUp() {
        questionsDb.clear();
        activeExamQuestionMap.clear();

        long now = System.currentTimeMillis();
        AuthToken lect = new AuthToken("u-lect-1", "gv_lan", "Nguyen Thi Lan", UserRole.LECTURER, now, now + 14400000L);
        lecturerToken = HmacUtil.createToken(lect, hmacKey);

        AuthToken stud = new AuthToken("u-stud-1", "sv_nam", "Tran Van Nam", UserRole.STUDENT, now, now + 14400000L);
        studentToken = HmacUtil.createToken(stud, hmacKey);

        inMemoryQuestionDao = new QuestionDao(null) {
            @Override
            public QuestionListResponse listQuestions(String keyword, String subject, Integer chapter, String difficulty, int page, int pageSize) {
                List<QuestionDto> list = new ArrayList<>();
                for (QuestionDto q : questionsDb.values()) {
                    if (q.getVersion() == null || q.getVersion() <= 0) continue;
                    list.add(q);
                }
                return new QuestionListResponse(list, list.size(), page, pageSize);
            }

            @Override
            public QuestionDto findQuestionById(String questionId) {
                return questionsDb.get(questionId);
            }

            @Override
            public boolean isQuestionInActiveExam(String questionId, long serverTime) {
                return Boolean.TRUE.equals(activeExamQuestionMap.get(questionId));
            }

            @Override
            public QuestionDto createQuestion(QuestionDto dto, String createdBy, SyncLogWriter writer) {
                String id = dto.getId() != null ? dto.getId() : UUID.randomUUID().toString();
                dto.setId(id);
                dto.setVersion(1);
                questionsDb.put(id, dto);
                return dto;
            }

            @Override
            public QuestionDto updateQuestion(QuestionDto dto, int baseVersion, SyncLogWriter writer, long serverTime)
                    throws ExamServerException {
                if (isQuestionInActiveExam(dto.getId(), serverTime)) {
                    throw new ResourceLockedException("Câu hỏi đang thuộc ca thi đang diễn ra, không thể sửa!");
                }
                QuestionDto cur = questionsDb.get(dto.getId());
                if (cur == null) {
                    throw new ExamServerException(ErrorCode.NOT_FOUND, "Không tìm thấy câu hỏi");
                }
                if (cur.getVersion() != baseVersion) {
                    throw new VersionConflictException("Xung đột phiên bản dữ liệu", cur);
                }
                int newVer = baseVersion + 1;
                dto.setVersion(newVer);
                questionsDb.put(dto.getId(), dto);
                return dto;
            }

            @Override
            public void deleteQuestion(String questionId, int baseVersion, SyncLogWriter writer, long serverTime)
                    throws ExamServerException {
                if (isQuestionInActiveExam(questionId, serverTime)) {
                    throw new ResourceLockedException("Câu hỏi đang thuộc ca thi đang diễn ra, không thể xóa!");
                }
                QuestionDto cur = questionsDb.get(questionId);
                if (cur == null) {
                    throw new ExamServerException(ErrorCode.NOT_FOUND, "Không tìm thấy câu hỏi");
                }
                if (cur.getVersion() != baseVersion) {
                    throw new VersionConflictException("Xung đột phiên bản dữ liệu", cur);
                }
                // Soft delete
                cur.setVersion(-1); // mark as deleted
            }
        };

        questionHandler = new QuestionHandler(inMemoryQuestionDao, null);
    }

    @Test
    @DisplayName("Two lecturers concurrently updating same question: one succeeds, other gets 409 conflict with current data")
    void testOptimisticConcurrencyConflict() throws Exception {
        // 1. Initial question created with version 1
        QuestionDto initial = new QuestionDto();
        initial.setId("q-101");
        initial.setSubject("LTM");
        initial.setChapter(1);
        initial.setDifficulty("EASY");
        initial.setContent("Giao thức TCP là gì?");
        initial.setVersion(1);
        initial.setOptions(Arrays.asList(
                new OptionDto("opt-1", "A", "Giao thức hướng kết nối", true),
                new OptionDto("opt-2", "B", "Giao thức không kết nối", false)
        ));
        questionsDb.put("q-101", initial);

        // 2. Lecturer 1 updates question with baseVersion = 1
        Message req1 = Message.request(MessageType.QUESTION_UPDATE, "req-lect1");
        req1.setToken(lecturerToken);
        QuestionDto update1 = new QuestionDto();
        update1.setId("q-101");
        update1.setSubject("LTM");
        update1.setChapter(1);
        update1.setDifficulty("MEDIUM");
        update1.setContent("Giao thức TCP (Transmission Control Protocol) là gì?");
        update1.setVersion(1); // baseVersion = 1
        update1.setOptions(initial.getOptions());
        req1.setPayload(update1);

        Message resp1 = questionHandler.handle(req1, null);
        assertEquals("OK", resp1.getStatus());
        assertEquals(200, resp1.getCode());

        QuestionDto saved1 = JsonUtil.convertValue(resp1.getPayload(), QuestionDto.class);
        assertEquals(2, saved1.getVersion());
        assertEquals("Giao thức TCP (Transmission Control Protocol) là gì?", saved1.getContent());

        // 3. Lecturer 2 (who opened the form earlier with baseVersion = 1) tries to update
        Message req2 = Message.request(MessageType.QUESTION_UPDATE, "req-lect2");
        req2.setToken(lecturerToken);
        QuestionDto update2 = new QuestionDto();
        update2.setId("q-101");
        update2.setSubject("LTM");
        update2.setChapter(1);
        update2.setDifficulty("HARD");
        update2.setContent("TCP là giao thức tầng nào?");
        update2.setVersion(1); // still baseVersion = 1
        update2.setOptions(initial.getOptions());
        req2.setPayload(update2);

        Message resp2 = questionHandler.handle(req2, null);
        // Expect 409 VERSION_CONFLICT
        assertEquals("ERROR", resp2.getStatus());
        assertEquals(ErrorCode.VERSION_CONFLICT.getCode(), resp2.getCode());

        // Verify payload returns the latest version 2 record for comparison dialog
        assertNotNull(resp2.getPayload());
        QuestionDto currentInDb = JsonUtil.convertValue(resp2.getPayload(), QuestionDto.class);
        assertEquals(2, currentInDb.getVersion());
        assertEquals("Giao thức TCP (Transmission Control Protocol) là gì?", currentInDb.getContent());

        // 4. Lecturer 2 resolves conflict and updates with baseVersion = 2
        update2.setVersion(2);
        Message req3 = Message.request(MessageType.QUESTION_UPDATE, "req-lect2-retry");
        req3.setToken(lecturerToken);
        req3.setPayload(update2);

        Message resp3 = questionHandler.handle(req3, null);
        assertEquals("OK", resp3.getStatus());
        QuestionDto saved3 = JsonUtil.convertValue(resp3.getPayload(), QuestionDto.class);
        assertEquals(3, saved3.getVersion());
        assertEquals("TCP là giao thức tầng nào?", saved3.getContent());
    }

    @Test
    @DisplayName("Invariant 11: Attempting to update or delete a question in active exam returns 423")
    void testActiveExamProtectionReturns423() throws Exception {
        QuestionDto q = new QuestionDto();
        q.setId("q-active-1");
        q.setSubject("CSDL");
        q.setContent("Câu hỏi thuộc ca thi đang diễn ra");
        q.setVersion(1);
        questionsDb.put("q-active-1", q);

        // Mark as active in ongoing exam
        activeExamQuestionMap.put("q-active-1", true);

        // Try to update -> 423 IN_USE_BY_ACTIVE_EXAM
        Message updateReq = Message.request(MessageType.QUESTION_UPDATE, "req-lock-upd");
        updateReq.setToken(lecturerToken);
        updateReq.setPayload(q);

        Message updateResp = questionHandler.handle(updateReq, null);
        assertEquals("ERROR", updateResp.getStatus());
        assertEquals(ErrorCode.IN_USE_BY_ACTIVE_EXAM.getCode(), updateResp.getCode());

        // Try to delete -> 423 IN_USE_BY_ACTIVE_EXAM
        Message deleteReq = Message.request(MessageType.QUESTION_DELETE, "req-lock-del");
        deleteReq.setToken(lecturerToken);
        Map<String, Object> delPayload = new HashMap<>();
        delPayload.put("questionId", "q-active-1");
        delPayload.put("baseVersion", 1);
        deleteReq.setPayload(delPayload);

        Message deleteResp = questionHandler.handle(deleteReq, null);
        assertEquals("ERROR", deleteResp.getStatus());
        assertEquals(ErrorCode.IN_USE_BY_ACTIVE_EXAM.getCode(), deleteResp.getCode());
    }

    @Test
    @DisplayName("Soft delete: inactive question is soft deleted and cannot be listed")
    void testSoftDelete() throws Exception {
        QuestionDto q = new QuestionDto();
        q.setId("q-del-1");
        q.setSubject("LTM");
        q.setContent("Câu hỏi cần xóa");
        q.setVersion(1);
        questionsDb.put("q-del-1", q);

        Message deleteReq = Message.request(MessageType.QUESTION_DELETE, "req-del");
        deleteReq.setToken(lecturerToken);
        Map<String, Object> delPayload = new HashMap<>();
        delPayload.put("questionId", "q-del-1");
        delPayload.put("baseVersion", 1);
        deleteReq.setPayload(delPayload);

        Message deleteResp = questionHandler.handle(deleteReq, null);
        assertEquals("OK", deleteResp.getStatus());

        // Check listing
        Message listReq = Message.request(MessageType.QUESTION_LIST, "req-list");
        listReq.setToken(lecturerToken);
        Message listResp = questionHandler.handle(listReq, null);
        QuestionListResponse listData = JsonUtil.convertValue(listResp.getPayload(), QuestionListResponse.class);
        assertTrue(listData.getQuestions().stream().noneMatch(x -> "q-del-1".equals(x.getId())));
    }

    @Test
    @DisplayName("Authorization check: Student receives 403 FORBIDDEN when attempting question operations")
    void testStudentForbidden() throws Exception {
        Message req = Message.request(MessageType.QUESTION_LIST, "req-stud");
        req.setToken(studentToken);

        Message resp = questionHandler.handle(req, null);
        assertEquals("ERROR", resp.getStatus());
        assertEquals(ErrorCode.FORBIDDEN.getCode(), resp.getCode());
    }
}
