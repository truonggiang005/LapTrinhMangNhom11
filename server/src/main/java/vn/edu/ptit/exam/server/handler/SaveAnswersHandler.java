package vn.edu.ptit.exam.server.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.common.config.Config;
import vn.edu.ptit.exam.common.dto.AnswerItemDto;
import vn.edu.ptit.exam.common.dto.AuthToken;
import vn.edu.ptit.exam.common.dto.Message;
import vn.edu.ptit.exam.common.enums.ErrorCode;
import vn.edu.ptit.exam.common.enums.MessageType;
import vn.edu.ptit.exam.common.model.Attempt;
import vn.edu.ptit.exam.common.util.HmacUtil;
import vn.edu.ptit.exam.common.util.JsonUtil;
import vn.edu.ptit.exam.server.dao.AttemptDao;
import vn.edu.ptit.exam.server.net.SocketContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles SAVE_ANSWERS requests (Luồng 4).
 * Enforces ownership, IN_PROGRESS state, deadline + 5s tolerance,
 * strictly increasing seq check, and transactional sync_log ANSWER_UPSERT.
 */
public class SaveAnswersHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(SaveAnswersHandler.class);

    private final AttemptDao attemptDao;
    private final String hmacSecretKey;
    private final long lateToleranceMs;

    // In-memory tracking of highest received seq per attempt: attemptId -> highestSeq
    private final ConcurrentHashMap<String, Long> lastSeenSeqMap = new ConcurrentHashMap<>();

    public SaveAnswersHandler(AttemptDao attemptDao) {
        this.attemptDao = attemptDao;
        Config config = Config.getInstance();
        this.hmacSecretKey = config.getHmacSecretKey();
        this.lateToleranceMs = config.getExamLateToleranceMs();
    }

    @Override
    public Message handle(Message request, SocketContext ctx) throws Exception {
        // 1. Verify token
        AuthToken token = HmacUtil.parseAndVerifyToken(request.getToken(), hmacSecretKey);
        if (token == null) {
            return Message.error(MessageType.SAVE_ANSWERS, request.getRequestId(), ErrorCode.UNAUTHENTICATED);
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
            return Message.error(MessageType.SAVE_ANSWERS, request.getRequestId(), ErrorCode.BAD_REQUEST, "Thiếu mã bài làm (attemptId)");
        }

        long now = System.currentTimeMillis();

        // 3. Load attempt and verify ownership
        Attempt attempt = attemptDao.findAttemptById(attemptId);
        if (attempt == null) {
            return Message.error(MessageType.SAVE_ANSWERS, request.getRequestId(), ErrorCode.ATTEMPT_NOT_FOUND, "Không tìm thấy bài làm");
        }

        if (!token.getUserId().equals(attempt.getStudentId())) {
            return Message.error(MessageType.SAVE_ANSWERS, request.getRequestId(), ErrorCode.FORBIDDEN, "Bạn không có quyền sửa bài thi này");
        }

        if (!"IN_PROGRESS".equalsIgnoreCase(attempt.getStatus())) {
            return Message.error(MessageType.SAVE_ANSWERS, request.getRequestId(), ErrorCode.ALREADY_SUBMITTED, "Bài thi đã được nộp hoặc đã kết thúc");
        }

        // 4. Verify deadline tolerance (deadline + 5 seconds)
        long deadline = attempt.getDeadline().getTime();
        if (now > deadline + lateToleranceMs) {
            return Message.error(MessageType.SAVE_ANSWERS, request.getRequestId(), ErrorCode.ATTEMPT_EXPIRED,
                    "Thời gian làm bài đã hết (vượt quá hạn nộp cho phép)");
        }

        // 5. Sequence check (seq must be greater than last seen seq for this attempt)
        Long seq = request.getSeq();
        if (seq == null && request.getPayload() != null) {
            JsonNode node = JsonUtil.readTree(JsonUtil.toJson(request.getPayload()));
            if (node.has("seq")) {
                seq = node.get("seq").asLong();
            }
        }

        if (seq != null) {
            Long lastSeen = lastSeenSeqMap.get(attemptId);
            if (lastSeen != null && seq <= lastSeen) {
                log.debug("Late/out-of-order SAVE_ANSWERS packet ignored for attempt {}: received seq={}, highest seq={}", attemptId, seq, lastSeen);
                // Return OK without overwriting newer answers
                Map<String, Object> respPayload = new HashMap<>();
                respPayload.put("savedCount", 0);
                respPayload.put("serverTime", now);
                respPayload.put("remainingSeconds", Math.max(0, (deadline - now) / 1000));
                return Message.ok(MessageType.SAVE_ANSWERS, request.getRequestId(), respPayload);
            }
            lastSeenSeqMap.put(attemptId, seq);
        }

        // 6. Extract answers list
        List<AnswerItemDto> answers = null;
        if (request.getPayload() != null) {
            JsonNode node = JsonUtil.readTree(JsonUtil.toJson(request.getPayload()));
            if (node.has("answers")) {
                answers = JsonUtil.convertValue(node.get("answers"), new TypeReference<List<AnswerItemDto>>() {});
            }
        }

        int savedCount = 0;
        if (answers != null && !answers.isEmpty()) {
            savedCount = attemptDao.saveAnswersBatch(attemptId, seq != null ? seq : 0L, answers);
        }

        Map<String, Object> respPayload = new HashMap<>();
        respPayload.put("savedCount", savedCount);
        respPayload.put("serverTime", now);
        respPayload.put("remainingSeconds", Math.max(0, (deadline - now) / 1000));

        Message response = Message.ok(MessageType.SAVE_ANSWERS, request.getRequestId(), respPayload);
        response.setAttemptId(attemptId);
        response.setSeq(seq);
        return response;
    }
}
