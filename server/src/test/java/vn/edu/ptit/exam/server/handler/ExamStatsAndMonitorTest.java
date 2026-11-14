package vn.edu.ptit.exam.server.handler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.edu.ptit.exam.common.config.Config;
import vn.edu.ptit.exam.common.dto.*;
import vn.edu.ptit.exam.common.enums.MessageType;
import vn.edu.ptit.exam.common.enums.UserRole;
import vn.edu.ptit.exam.common.util.HmacUtil;
import vn.edu.ptit.exam.common.util.JsonUtil;
import vn.edu.ptit.exam.server.dao.ExamDao;

import java.sql.SQLException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class ExamStatsAndMonitorTest {

    private ExamDao inMemoryExamDao;
    private ExamAdminHandler examAdminHandler;
    private final String hmacKey = Config.getInstance().getHmacSecretKey();
    private String lecturerToken;

    @BeforeEach
    void setUp() {
        long now = System.currentTimeMillis();
        AuthToken lect = new AuthToken("u-lect-3", "gv_hoang", "Pham Van Hoang", UserRole.LECTURER, now, now + 14400000L);
        lecturerToken = HmacUtil.createToken(lect, hmacKey);

        inMemoryExamDao = new ExamDao(null) {
            @Override
            public ExamMonitorDto getExamMonitor(String examId) {
                // 100 assigned: 20 not started, 30 in-progress, 50 submitted
                return new ExamMonitorDto(examId, "Ca thi Giua Ky", 100, 20, 30, 50, System.currentTimeMillis());
            }

            @Override
            public ScoreListResponse getScores(String examId, String keyword, int page, int pageSize, String sortBy) {
                List<ScoreItemDto> list = new ArrayList<>();
                list.add(new ScoreItemDto("s1", "B21DCCN001", "An Nguyen", 8.5, 17, 20, System.currentTimeMillis(), false, "SUBMITTED"));
                list.add(new ScoreItemDto("s2", "B21DCCN002", "Binh Le", 6.0, 12, 20, System.currentTimeMillis(), false, "SUBMITTED"));
                list.add(new ScoreItemDto("s3", "B21DCCN003", "Cuong Tran", 4.5, 9, 20, System.currentTimeMillis(), true, "EXPIRED"));

                boolean isFinalized = "exam-final".equals(examId);
                return new ScoreListResponse(list, 3, page, pageSize, isFinalized);
            }

            @Override
            public ExamStatsDto getStats(String examId) {
                // Scores: 8.5, 6.0, 4.5, 9.5, 7.5 -> Avg: 7.2, Min: 4.5, Max: 9.5
                Map<String, Integer> dist = new LinkedHashMap<>();
                dist.put("< 5.0", 1);
                dist.put("5.0 - < 7.0", 1);
                dist.put("7.0 - < 8.5", 1);
                dist.put("8.5 - 10.0", 2);

                Map<String, Double> accuracy = new LinkedHashMap<>();
                accuracy.put("q-01", 100.0);
                accuracy.put("q-02", 60.0);
                accuracy.put("q-03", 20.0); // Hard question / high error rate

                return new ExamStatsDto(examId, 7.2, 9.5, 4.5, 5, dist, accuracy);
            }
        };

        examAdminHandler = new ExamAdminHandler(inMemoryExamDao, null, null, null, "server-1");
    }

    @Test
    @DisplayName("EXAM_MONITOR: accurately returns real-time candidate counts")
    void testExamMonitor() throws Exception {
        Message req = Message.request(MessageType.EXAM_MONITOR, "req-mon");
        req.setToken(lecturerToken);
        Map<String, Object> payload = new HashMap<>();
        payload.put("examId", "exam-101");
        req.setPayload(payload);

        Message resp = examAdminHandler.handle(req, null);
        assertEquals("OK", resp.getStatus());

        ExamMonitorDto monitor = JsonUtil.convertValue(resp.getPayload(), ExamMonitorDto.class);
        assertEquals("exam-101", monitor.getExamId());
        assertEquals("Ca thi Giua Ky", monitor.getExamTitle());
        assertEquals(100, monitor.getTotalAssigned());
        assertEquals(20, monitor.getNotStartedCount());
        assertEquals(30, monitor.getInProgressCount());
        assertEquals(50, monitor.getSubmittedCount());
    }

    @Test
    @DisplayName("GET_SCORES: returns student scores, search, and finalized status")
    void testGetScores() throws Exception {
        // Test ongoing exam (not finalized)
        Message req = Message.request(MessageType.GET_SCORES, "req-scores");
        req.setToken(lecturerToken);
        Map<String, Object> payload = new HashMap<>();
        payload.put("examId", "exam-active");
        payload.put("page", 1);
        payload.put("pageSize", 10);
        req.setPayload(payload);

        Message resp = examAdminHandler.handle(req, null);
        assertEquals("OK", resp.getStatus());
        ScoreListResponse response = JsonUtil.convertValue(resp.getPayload(), ScoreListResponse.class);
        assertFalse(response.isFinalized(), "Should be unfinalized ('Tạm thời') for active exam");
        assertEquals(3, response.getScores().size());

        // Test finalized exam
        payload.put("examId", "exam-final");
        req.setPayload(payload);
        Message respFinal = examAdminHandler.handle(req, null);
        ScoreListResponse responseFinal = JsonUtil.convertValue(respFinal.getPayload(), ScoreListResponse.class);
        assertTrue(responseFinal.isFinalized(), "Should be finalized ('Đã chốt')");
    }

    @Test
    @DisplayName("GET_STATS: calculates avg, min, max, distribution buckets, and per-question accuracy")
    void testGetStats() throws Exception {
        Message req = Message.request(MessageType.GET_STATS, "req-stats");
        req.setToken(lecturerToken);
        Map<String, Object> payload = new HashMap<>();
        payload.put("examId", "exam-101");
        req.setPayload(payload);

        Message resp = examAdminHandler.handle(req, null);
        assertEquals("OK", resp.getStatus());

        ExamStatsDto stats = JsonUtil.convertValue(resp.getPayload(), ExamStatsDto.class);
        assertEquals(7.2, stats.getAverageScore(), 0.01);
        assertEquals(9.5, stats.getHighestScore(), 0.01);
        assertEquals(4.5, stats.getLowestScore(), 0.01);
        assertEquals(5, stats.getTotalAttempts());

        // Score brackets
        assertEquals(1, stats.getScoreDistribution().get("< 5.0"));
        assertEquals(1, stats.getScoreDistribution().get("5.0 - < 7.0"));
        assertEquals(1, stats.getScoreDistribution().get("7.0 - < 8.5"));
        assertEquals(2, stats.getScoreDistribution().get("8.5 - 10.0"));

        // Question accuracies
        assertEquals(100.0, stats.getQuestionAccuracy().get("q-01"), 0.01);
        assertEquals(60.0, stats.getQuestionAccuracy().get("q-02"), 0.01);
        assertEquals(20.0, stats.getQuestionAccuracy().get("q-03"), 0.01); // Identify hardest question
    }
}
