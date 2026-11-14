package vn.edu.ptit.exam.server.handler;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import vn.edu.ptit.exam.server.net.SocketContext;
import vn.edu.ptit.exam.server.service.ExamEngine;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Handles START_EXAM requests (Luồng 3).
 * Validates assignment, verifies exam window, creates attempt in a transaction with ATTEMPT_CREATE sync_log,
 * signs ExamTicket, and builds deterministically shuffled questions without correct answers.
 * If attempt already IN_PROGRESS, recovers it with saved answers.
 */
public class StartExamHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(StartExamHandler.class);

    private final AttemptDao attemptDao;
    private final QuestionDao questionDao;
    private final String serverId;
    private final String hmacSecretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public StartExamHandler(AttemptDao attemptDao, QuestionDao questionDao, String serverId) {
        this.attemptDao = attemptDao;
        this.questionDao = questionDao;
        this.serverId = serverId;
        this.hmacSecretKey = Config.getInstance().getHmacSecretKey();
    }

    @Override
    public Message handle(Message request, SocketContext ctx) throws Exception {
        // 1. Verify token
        AuthToken token = HmacUtil.parseAndVerifyToken(request.getToken(), hmacSecretKey);
        if (token == null) {
            return Message.error(MessageType.START_EXAM, request.getRequestId(), ErrorCode.UNAUTHENTICATED);
        }

        if (token.getRole() != UserRole.STUDENT) {
            return Message.error(MessageType.START_EXAM, request.getRequestId(), ErrorCode.FORBIDDEN,
                    "Chỉ sinh viên mới được tham gia làm bài thi");
        }

        // 2. Extract examId from payload
        String examId = null;
        if (request.getPayload() != null) {
            JsonNode node = JsonUtil.readTree(JsonUtil.toJson(request.getPayload()));
            if (node.has("examId")) {
                examId = node.get("examId").asText().trim();
            }
        }

        if (examId == null || examId.isEmpty()) {
            return Message.error(MessageType.START_EXAM, request.getRequestId(), ErrorCode.BAD_REQUEST, "Thiếu mã ca thi (examId)");
        }

        long now = System.currentTimeMillis();

        // 3. Verify student is assigned to this exam
        if (!attemptDao.isStudentAssignedToExam(token.getUserId(), examId)) {
            return Message.error(MessageType.START_EXAM, request.getRequestId(), ErrorCode.NOT_ASSIGNED,
                    "Sinh viên chưa được phân công vào ca thi này");
        }

        // 4. Verify exam timing window
        Map<String, Object> examInfo = attemptDao.getExamInfo(examId);
        if (examInfo == null) {
            return Message.error(MessageType.START_EXAM, request.getRequestId(), ErrorCode.NOT_FOUND, "Không tìm thấy ca thi");
        }

        long startTime = (Long) examInfo.get("startTime");
        long endTime = (Long) examInfo.get("endTime");
        int durationMinutes = (Integer) examInfo.get("durationMinutes");
        String examTitle = (String) examInfo.get("title");
        String subject = (String) examInfo.get("subject");

        if (now < startTime) {
            return Message.error(MessageType.START_EXAM, request.getRequestId(), ErrorCode.EXAM_NOT_OPEN,
                    "Chưa tới giờ mở ca thi. Ca thi mở lúc: " + new Timestamp(startTime));
        }

        if (now > endTime) {
            return Message.error(MessageType.START_EXAM, request.getRequestId(), ErrorCode.EXAM_CLOSED,
                    "Ca thi đã kết thúc vào lúc: " + new Timestamp(endTime));
        }

        // 5. Check existing attempt for this student and exam
        Attempt existing = attemptDao.findAttemptByStudentAndExam(token.getUserId(), examId);
        if (existing != null) {
            if ("SUBMITTED".equalsIgnoreCase(existing.getStatus())) {
                return Message.error(MessageType.START_EXAM, request.getRequestId(), ErrorCode.ALREADY_SUBMITTED,
                        "Bạn đã nộp bài thi cho ca thi này rồi");
            }

            // Recovery: Attempt is already IN_PROGRESS!
            log.info("Recovering IN_PROGRESS attempt {} for student {}", existing.getId(), token.getUsername());
            Map<String, AnswerItemDto> savedAnswers = attemptDao.loadSavedAnswers(existing.getId());
            List<Question> questions = questionDao.getExamQuestions(examId);
            List<QuestionDto> shuffledQuestions = ExamEngine.buildShuffledQuestions(questions, existing.getSeed(), savedAnswers);

            ExamTicket ticket = new ExamTicket(existing.getId(), token.getUserId(), examId,
                    existing.getSeed(), existing.getStartTime().getTime(), existing.getDeadline().getTime(), existing.getServerId());
            HmacUtil.signExamTicket(ticket, hmacSecretKey);

            StartExamResponse respPayload = new StartExamResponse();
            respPayload.setAttemptId(existing.getId());
            respPayload.setTicket(ticket);
            respPayload.setExamId(examId);
            respPayload.setExamTitle(examTitle);
            respPayload.setSubject(subject);
            respPayload.setDurationMinutes(durationMinutes);
            respPayload.setStartTime(existing.getStartTime().getTime());
            respPayload.setDeadline(existing.getDeadline().getTime());
            respPayload.setServerTime(now);
            respPayload.setRemainingSeconds(Math.max(0, (existing.getDeadline().getTime() - now) / 1000));
            respPayload.setQuestions(shuffledQuestions);
            respPayload.setRecovered(true);

            Message response = Message.ok(MessageType.START_EXAM, request.getRequestId(), respPayload);
            response.setAttemptId(existing.getId());
            return response;
        }

        // 6. Create brand new attempt
        String attemptId = UUID.randomUUID().toString();
        long seed = secureRandom.nextLong();
        long deadline = Math.min(now + durationMinutes * 60000L, endTime);

        List<Question> questions = questionDao.getExamQuestions(examId);

        Attempt attempt = new Attempt();
        attempt.setId(attemptId);
        attempt.setExamId(examId);
        attempt.setStudentId(token.getUserId());
        attempt.setSeed(seed);
        attempt.setStartTime(new Timestamp(now));
        attempt.setDeadline(new Timestamp(deadline));
        attempt.setServerId(serverId);
        attempt.setTotalQuestions(questions.size());

        // Save attempt + ATTEMPT_CREATE sync_log in transaction
        attemptDao.createAttempt(attempt);

        // Sign ExamTicket
        ExamTicket ticket = new ExamTicket(attemptId, token.getUserId(), examId, seed, now, deadline, serverId);
        HmacUtil.signExamTicket(ticket, hmacSecretKey);

        // Build deterministically shuffled questions
        List<QuestionDto> shuffledQuestions = ExamEngine.buildShuffledQuestions(questions, seed, null);

        StartExamResponse respPayload = new StartExamResponse();
        respPayload.setAttemptId(attemptId);
        respPayload.setTicket(ticket);
        respPayload.setExamId(examId);
        respPayload.setExamTitle(examTitle);
        respPayload.setSubject(subject);
        respPayload.setDurationMinutes(durationMinutes);
        respPayload.setStartTime(now);
        respPayload.setDeadline(deadline);
        respPayload.setServerTime(now);
        respPayload.setRemainingSeconds(Math.max(0, (deadline - now) / 1000));
        respPayload.setQuestions(shuffledQuestions);
        respPayload.setRecovered(false);

        Message response = Message.ok(MessageType.START_EXAM, request.getRequestId(), respPayload);
        response.setAttemptId(attemptId);
        return response;
    }
}
