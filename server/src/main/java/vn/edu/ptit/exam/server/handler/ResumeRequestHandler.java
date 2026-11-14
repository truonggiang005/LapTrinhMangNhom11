package vn.edu.ptit.exam.server.handler;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.common.config.Config;
import vn.edu.ptit.exam.common.dto.*;
import vn.edu.ptit.exam.common.enums.ErrorCode;
import vn.edu.ptit.exam.common.enums.MessageType;
import vn.edu.ptit.exam.common.model.Attempt;
import vn.edu.ptit.exam.common.model.Question;
import vn.edu.ptit.exam.common.util.HmacUtil;
import vn.edu.ptit.exam.common.util.JsonUtil;
import vn.edu.ptit.exam.server.dao.AttemptDao;
import vn.edu.ptit.exam.server.dao.QuestionDao;
import vn.edu.ptit.exam.server.net.SocketContext;
import vn.edu.ptit.exam.server.sync.SyncManager;

import java.sql.Timestamp;
import java.util.*;

/**
 * Handles RESUME_REQUEST (and RESUME_SESSION) during failover / reconnection (Luồng 8 & 9).
 * - Validates HMAC token and/or ExamTicket.
 * - Finds attempt in local database.
 * - If missing, attempts to reconstruct from valid ExamTicket.
 * - If already SUBMITTED, returns cached result deterministically.
 * - If IN_PROGRESS, reassigns ownership with ATTEMPT_REASSIGN (updates server_id, increments version, logs sync_log).
 * - Returns RESUME_OK with remaining seconds (without extending deadline), attempt status, and saved answers.
 */
public class ResumeRequestHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(ResumeRequestHandler.class);

    private final AttemptDao attemptDao;
    private final QuestionDao questionDao;
    private final String serverId;
    private final String hmacSecretKey;
    private final SyncManager syncManager;

    public ResumeRequestHandler(AttemptDao attemptDao, QuestionDao questionDao, String serverId) {
        this(attemptDao, questionDao, serverId, null);
    }

    public ResumeRequestHandler(AttemptDao attemptDao, QuestionDao questionDao, String serverId, SyncManager syncManager) {
        this.attemptDao = attemptDao;
        this.questionDao = questionDao;
        this.serverId = serverId;
        this.hmacSecretKey = Config.getInstance().getHmacSecretKey();
        this.syncManager = syncManager;
    }

    @Override
    public Message handle(Message request, SocketContext ctx) throws Exception {
        String tokenStr = request.getToken();
        AuthToken authToken = null;
        if (tokenStr != null) {
            authToken = HmacUtil.parseAndVerifyToken(tokenStr, hmacSecretKey);
        }

        ExamTicket ticket = null;
        String attemptId = request.getAttemptId();
        String studentId = authToken != null ? authToken.getUserId() : null;
        String examId = null;

        // Parse payload if present
        if (request.getPayload() != null) {
            try {
                JsonNode node = JsonUtil.readTree(JsonUtil.toJson(request.getPayload()));
                if (attemptId == null && node.has("attemptId")) {
                    attemptId = node.get("attemptId").asText().trim();
                }
                if (studentId == null && node.has("userId")) {
                    studentId = node.get("userId").asText().trim();
                }
                if (examId == null && node.has("examId")) {
                    examId = node.get("examId").asText().trim();
                }
                if (node.has("ticket")) {
                    ticket = JsonUtil.fromJson(node.get("ticket").toString(), ExamTicket.class);
                }
            } catch (Exception e) {
                log.warn("Failed to parse RESUME_REQUEST payload: {}", e.getMessage());
            }
        }

        // Verify ticket if present
        boolean ticketValid = false;
        if (ticket != null) {
            ticketValid = HmacUtil.verifyExamTicket(ticket, hmacSecretKey);
            if (!ticketValid) {
                log.warn("Provided ExamTicket failed HMAC verification for attempt {}", ticket.getAttemptId());
            } else {
                if (attemptId == null) {
                    attemptId = ticket.getAttemptId();
                }
                if (studentId == null) {
                    studentId = ticket.getStudentId();
                }
                if (examId == null) {
                    examId = ticket.getExamId();
                }
            }
        }

        if (attemptId == null && studentId != null && examId != null) {
            Attempt existing = attemptDao.findAttemptByStudentAndExam(studentId, examId);
            if (existing != null) {
                attemptId = existing.getId();
            }
        }

        if (attemptId == null) {
            return Message.error(MessageType.RESUME_REQUEST, request.getRequestId(), ErrorCode.BAD_REQUEST,
                    "Thiếu mã bài thi (attemptId) để khôi phục");
        }

        long now = System.currentTimeMillis();

        // 1. Find attempt in local DB
        Attempt attempt = attemptDao.findAttemptById(attemptId);

        // 2. If not found in DB, try reconstructing from verified ExamTicket
        if (attempt == null) {
            if (ticket != null && ticketValid) {
                attempt = new Attempt();
                attempt.setId(ticket.getAttemptId());
                attempt.setStudentId(ticket.getStudentId());
                attempt.setExamId(ticket.getExamId());
                attempt.setSeed(ticket.getSeed());
                attempt.setStartTime(new Timestamp(ticket.getStartTime()));
                attempt.setDeadline(new Timestamp(ticket.getDeadline()));
                attempt.setServerId(serverId);
                attempt.setStatus("IN_PROGRESS");
                List<Question> questions = questionDao != null ? questionDao.getExamQuestions(ticket.getExamId()) : Collections.emptyList();
                attempt.setTotalQuestions(questions != null ? questions.size() : 0);

                attemptDao.createAttempt(attempt);
                log.info("Reconstructed attempt {} on {} from verified ExamTicket", attemptId, serverId);
            } else {
                return Message.error(MessageType.RESUME_REQUEST, request.getRequestId(), ErrorCode.NOT_FOUND,
                        "Không tìm thấy bài thi để khôi phục");
            }
        }

        // Verify user ownership if studentId is known
        if (studentId != null && !studentId.equalsIgnoreCase(attempt.getStudentId())) {
            return Message.error(MessageType.RESUME_REQUEST, request.getRequestId(), ErrorCode.FORBIDDEN,
                    "Bài thi không thuộc về người dùng hiện tại");
        }

        // 3. Handle already SUBMITTED attempt
        if ("SUBMITTED".equalsIgnoreCase(attempt.getStatus())) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("attemptId", attempt.getId());
            payload.put("examId", attempt.getExamId());
            payload.put("status", "SUBMITTED");
            payload.put("score", attempt.getScore());
            payload.put("correctCount", attempt.getCorrectCount());
            payload.put("totalQuestions", attempt.getTotalQuestions());
            payload.put("remainingSeconds", 0);
            payload.put("serverId", serverId);
            payload.put("serverTime", now);

            Message resp = Message.ok(MessageType.RESUME_OK, request.getRequestId(), payload);
            resp.setAttemptId(attempt.getId());
            resp.setServerTime(now);
            return resp;
        }

        // 4. Handle IN_PROGRESS attempt
        long deadlineMs = attempt.getDeadline() != null ? attempt.getDeadline().getTime() : now;
        long remainingSeconds = Math.max(0, (deadlineMs - now) / 1000);

        // Reassign attempt to this server (Luồng 8 Bước 5)
        boolean reassigned = attemptDao.reassignAttempt(attemptId, serverId);
        if (!reassigned) {
            log.warn("Attempt {} could not be reassigned to {}", attemptId, serverId);
        }

        // Load saved answers
        Map<String, AnswerItemDto> savedAnswers = attemptDao.loadSavedAnswers(attemptId);

        Map<String, Object> payload = new HashMap<>();
        payload.put("attemptId", attempt.getId());
        payload.put("examId", attempt.getExamId());
        payload.put("status", "IN_PROGRESS");
        payload.put("remainingSeconds", remainingSeconds);
        payload.put("deadline", deadlineMs);
        payload.put("startTime", attempt.getStartTime() != null ? attempt.getStartTime().getTime() : now);
        payload.put("serverId", serverId);
        payload.put("answers", savedAnswers != null ? new ArrayList<>(savedAnswers.values()) : Collections.emptyList());

        Message resp = Message.ok(MessageType.RESUME_OK, request.getRequestId(), payload);
        resp.setAttemptId(attempt.getId());
        resp.setServerTime(now);
        return resp;
    }
}
