package vn.edu.ptit.exam.server.handler;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.common.config.Config;
import vn.edu.ptit.exam.common.dto.AnswerItemDto;
import vn.edu.ptit.exam.common.dto.AuthToken;
import vn.edu.ptit.exam.common.dto.Message;
import vn.edu.ptit.exam.common.dto.SubmitResultDto;
import vn.edu.ptit.exam.common.enums.ErrorCode;
import vn.edu.ptit.exam.common.enums.MessageType;
import vn.edu.ptit.exam.common.model.Attempt;
import vn.edu.ptit.exam.common.util.HmacUtil;
import vn.edu.ptit.exam.common.util.JsonUtil;
import vn.edu.ptit.exam.server.dao.AttemptDao;
import vn.edu.ptit.exam.server.dao.AuditLogDao;
import vn.edu.ptit.exam.server.dao.QuestionDao;
import vn.edu.ptit.exam.server.net.SocketContext;
import vn.edu.ptit.exam.server.service.ExamEngine;

import java.util.Map;

/**
 * Handles SUBMIT requests (Luồng 5).
 * Invariant 4: Fully idempotent submission via atomic UPDATE ... WHERE status = 'IN_PROGRESS'.
 * Invariant 5: Deterministic scoring with 2-decimal rounding.
 */
public class SubmitHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(SubmitHandler.class);

    private final AttemptDao attemptDao;
    private final QuestionDao questionDao;
    private final AuditLogDao auditLogDao;
    private final String serverId;
    private final String hmacSecretKey;
    private final long lateToleranceMs;
    private final vn.edu.ptit.exam.server.sync.SyncManager syncManager;
    private final boolean replicationWaitEnabled;

    public SubmitHandler(AttemptDao attemptDao, QuestionDao questionDao, AuditLogDao auditLogDao, String serverId) {
        this(attemptDao, questionDao, auditLogDao, serverId, null);
    }

    public SubmitHandler(AttemptDao attemptDao, QuestionDao questionDao, AuditLogDao auditLogDao,
                         String serverId, vn.edu.ptit.exam.server.sync.SyncManager syncManager) {
        this.attemptDao = attemptDao;
        this.questionDao = questionDao;
        this.auditLogDao = auditLogDao;
        this.serverId = serverId;
        this.syncManager = syncManager;
        Config config = Config.getInstance();
        this.hmacSecretKey = config.getHmacSecretKey();
        this.lateToleranceMs = config.getExamLateToleranceMs();
        this.replicationWaitEnabled = config.getBooleanProperty("sync.replicationWaitEnabled", true);
    }

    @Override
    public Message handle(Message request, SocketContext ctx) throws Exception {
        // 1. Verify token
        AuthToken token = HmacUtil.parseAndVerifyToken(request.getToken(), hmacSecretKey);
        if (token == null) {
            return Message.error(MessageType.SUBMIT, request.getRequestId(), ErrorCode.UNAUTHENTICATED);
        }

        // 2. Identify attemptId
        String attemptId = request.getAttemptId();
        if (attemptId == null && request.getPayload() != null) {
            JsonNode node = JsonUtil.readTree(JsonUtil.toJson(request.getPayload()));
            if (node.has("attemptId")) {
                attemptId = node.get("attemptId").asText().trim();
            }
        }

        if (attemptId == null || attemptId.isEmpty()) {
            return Message.error(MessageType.SUBMIT, request.getRequestId(), ErrorCode.BAD_REQUEST, "Thiếu mã bài làm (attemptId)");
        }

        long now = System.currentTimeMillis();

        // 3. Load attempt and verify ownership
        Attempt attempt = attemptDao.findAttemptById(attemptId);
        if (attempt == null) {
            return Message.error(MessageType.SUBMIT, request.getRequestId(), ErrorCode.ATTEMPT_NOT_FOUND, "Không tìm thấy bài làm");
        }

        if (!token.getUserId().equals(attempt.getStudentId())) {
            return Message.error(MessageType.SUBMIT, request.getRequestId(), ErrorCode.FORBIDDEN, "Bạn không có quyền nộp bài thi này");
        }

        // Invariant 4: If attempt is ALREADY submitted, return existing result without re-grading!
        if ("SUBMITTED".equalsIgnoreCase(attempt.getStatus())) {
            log.info("Attempt {} was already submitted. Returning cached result (Idempotent).", attemptId);
            return buildSubmitResponse(attempt, request.getRequestId(), now);
        }

        // 4. Check for late submission
        long deadline = attempt.getDeadline().getTime();
        boolean isLate = now > deadline + lateToleranceMs;

        // 5. Load answer key from question cache
        Map<String, String> answerKey = questionDao.getCorrectAnswerMap(attempt.getExamId());

        // 6. Load saved student answers
        Map<String, AnswerItemDto> studentAnswers = attemptDao.loadSavedAnswers(attemptId);

        // 7. Get exam scoring info (maxScore, showScoreType)
        Map<String, Object> examInfo = attemptDao.getExamInfo(attempt.getExamId());
        double maxScore = examInfo != null && examInfo.get("maxScore") != null ? (Double) examInfo.get("maxScore") : 10.0;

        // Invariant 5: Deterministic scoring
        ExamEngine.ScoringResult scoring = ExamEngine.calculateScore(answerKey, studentAnswers, maxScore);

        // Invariant 4: Atomic idempotent update in database
        String opId = attemptDao.submitAttemptAndGetOpId(attemptId, scoring.getScore(), scoring.getCorrectCount(), isLate);

        if (opId == null) {
            // Another thread / node submitted concurrently! Re-read DB
            log.info("Concurrent submit detected for attempt {}. Re-reading DB state.", attemptId);
            Attempt reloaded = attemptDao.findAttemptById(attemptId);
            return buildSubmitResponse(reloaded, request.getRequestId(), now);
        }

        // Luồng 5 Bước 6: waitForReplication(opId, minAcks = 1, timeout = 2s)
        if (syncManager != null && replicationWaitEnabled) {
            boolean replicated = syncManager.waitForReplication(opId, 1, 2000L);
            if (!replicated) {
                log.warn("Submission for attempt {} opId {} not yet replicated after 2s, returning OK but marking pending",
                        attemptId, opId);
            }
        }

        // 8. Audit log
        auditLogDao.log("SUBMIT", token.getUserId(), "ATTEMPT", attemptId, serverId,
                ctx != null ? ctx.getRemoteAddress() : "SYSTEM",
                String.format("Nộp bài thi [Điểm: %.2f, Đúng: %d/%d, Muộn: %b]",
                        scoring.getScore(), scoring.getCorrectCount(), scoring.getTotalQuestions(), isLate));

        // 9. Construct response according to score visibility settings
        SubmitResultDto resultDto = new SubmitResultDto(
                attemptId,
                attempt.getExamId(),
                "SUBMITTED",
                shouldShowScore(examInfo, now) ? scoring.getScore() : null,
                scoring.getCorrectCount(),
                scoring.getTotalQuestions(),
                isLate,
                now
        );
        resultDto.setMessage(isLate ? "Bài thi đã được nộp muộn thành công" : "Nộp bài thi thành công");

        Message response = Message.ok(MessageType.SUBMIT, request.getRequestId(), resultDto);
        response.setAttemptId(attemptId);
        response.setServerTime(now);
        return response;
    }

    /**
     * Helper to grade and auto-submit an expired attempt triggered by background scheduler (actor = SYSTEM).
     */
    public boolean autoSubmitExpiredAttempt(Attempt attempt) {
        try {
            long now = System.currentTimeMillis();
            if ("SUBMITTED".equalsIgnoreCase(attempt.getStatus())) {
                return true;
            }

            Map<String, String> answerKey = questionDao.getCorrectAnswerMap(attempt.getExamId());
            Map<String, AnswerItemDto> studentAnswers = attemptDao.loadSavedAnswers(attempt.getId());

            Map<String, Object> examInfo = attemptDao.getExamInfo(attempt.getExamId());
            double maxScore = examInfo != null && examInfo.get("maxScore") != null ? (Double) examInfo.get("maxScore") : 10.0;

            ExamEngine.ScoringResult scoring = ExamEngine.calculateScore(answerKey, studentAnswers, maxScore);

            boolean updated = attemptDao.submitAttemptIdempotent(attempt.getId(), scoring.getScore(), scoring.getCorrectCount(), true);
            if (updated) {
                auditLogDao.log("SUBMIT_AUTO_EXPIRED", attempt.getStudentId(), "ATTEMPT", attempt.getId(), serverId,
                        "SYSTEM", "Hệ thống tự động nộp bài quá hạn");
                log.info("System auto-submitted expired attempt {} for student {} [Score: {}]",
                        attempt.getId(), attempt.getStudentId(), scoring.getScore());
            }
            return updated;
        } catch (Exception e) {
            log.error("Error auto-submitting expired attempt {}: {}", attempt.getId(), e.getMessage(), e);
            return false;
        }
    }

    private Message buildSubmitResponse(Attempt attempt, String requestId, long now) {
        try {
            Map<String, Object> examInfo = attemptDao.getExamInfo(attempt.getExamId());
            SubmitResultDto resultDto = new SubmitResultDto(
                    attempt.getId(),
                    attempt.getExamId(),
                    attempt.getStatus(),
                    shouldShowScore(examInfo, now) ? attempt.getScore() : null,
                    attempt.getCorrectCount(),
                    attempt.getTotalQuestions(),
                    attempt.isLate(),
                    attempt.getSubmitTime() != null ? attempt.getSubmitTime().getTime() : now
            );
            resultDto.setMessage("Bài thi đã được nộp");

            Message response = Message.ok(MessageType.SUBMIT, requestId, resultDto);
            response.setAttemptId(attempt.getId());
            response.setServerTime(now);
            return response;
        } catch (Exception e) {
            log.error("Error building submit response for {}: {}", attempt.getId(), e.getMessage(), e);
            return Message.error(MessageType.SUBMIT, requestId, ErrorCode.INTERNAL, "Lỗi khi đọc kết quả nộp bài");
        }
    }

    private boolean shouldShowScore(Map<String, Object> examInfo, long now) {
        if (examInfo == null) return true;
        String showScoreType = (String) examInfo.get("showScoreType");
        if ("IMMEDIATE".equalsIgnoreCase(showScoreType)) return true;
        if ("AFTER_EXAM_CLOSED".equalsIgnoreCase(showScoreType)) {
            Long endTime = (Long) examInfo.get("endTime");
            return endTime != null && now > endTime;
        }
        return false; // NEVER
    }
}
