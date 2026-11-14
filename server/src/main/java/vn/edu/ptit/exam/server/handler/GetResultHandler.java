package vn.edu.ptit.exam.server.handler;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.common.config.Config;
import vn.edu.ptit.exam.common.dto.AuthToken;
import vn.edu.ptit.exam.common.dto.Message;
import vn.edu.ptit.exam.common.dto.SubmitResultDto;
import vn.edu.ptit.exam.common.enums.ErrorCode;
import vn.edu.ptit.exam.common.enums.MessageType;
import vn.edu.ptit.exam.common.model.Attempt;
import vn.edu.ptit.exam.common.util.HmacUtil;
import vn.edu.ptit.exam.common.util.JsonUtil;
import vn.edu.ptit.exam.server.dao.AttemptDao;
import vn.edu.ptit.exam.server.net.SocketContext;

import java.util.Map;

/**
 * Handles GET_RESULT requests for completed exam attempts.
 */
public class GetResultHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(GetResultHandler.class);

    private final AttemptDao attemptDao;
    private final String hmacSecretKey;

    public GetResultHandler(AttemptDao attemptDao) {
        this.attemptDao = attemptDao;
        this.hmacSecretKey = Config.getInstance().getHmacSecretKey();
    }

    @Override
    public Message handle(Message request, SocketContext ctx) throws Exception {
        AuthToken token = HmacUtil.parseAndVerifyToken(request.getToken(), hmacSecretKey);
        if (token == null) {
            return Message.error(MessageType.GET_RESULT, request.getRequestId(), ErrorCode.UNAUTHENTICATED);
        }

        String attemptId = request.getAttemptId();
        if (attemptId == null && request.getPayload() != null) {
            JsonNode node = JsonUtil.readTree(JsonUtil.toJson(request.getPayload()));
            if (node.has("attemptId")) {
                attemptId = node.get("attemptId").asText().trim();
            }
        }

        if (attemptId == null || attemptId.isEmpty()) {
            return Message.error(MessageType.GET_RESULT, request.getRequestId(), ErrorCode.BAD_REQUEST, "Thiếu mã bài làm");
        }

        Attempt attempt = attemptDao.findAttemptById(attemptId);
        if (attempt == null) {
            return Message.error(MessageType.GET_RESULT, request.getRequestId(), ErrorCode.ATTEMPT_NOT_FOUND, "Không tìm thấy bài làm");
        }

        if (!token.getUserId().equals(attempt.getStudentId())) {
            return Message.error(MessageType.GET_RESULT, request.getRequestId(), ErrorCode.FORBIDDEN, "Bạn không có quyền xem bài làm này");
        }

        long now = System.currentTimeMillis();
        Map<String, Object> examInfo = attemptDao.getExamInfo(attempt.getExamId());
        boolean showScore = true;
        if (examInfo != null && "AFTER_EXAM_CLOSED".equalsIgnoreCase((String) examInfo.get("showScoreType"))) {
            Long endTime = (Long) examInfo.get("endTime");
            showScore = endTime != null && now > endTime;
        } else if (examInfo != null && "NEVER".equalsIgnoreCase((String) examInfo.get("showScoreType"))) {
            showScore = false;
        }

        SubmitResultDto dto = new SubmitResultDto(
                attempt.getId(),
                attempt.getExamId(),
                attempt.getStatus(),
                showScore ? attempt.getScore() : null,
                attempt.getCorrectCount(),
                attempt.getTotalQuestions(),
                attempt.isLate(),
                attempt.getSubmitTime() != null ? attempt.getSubmitTime().getTime() : now
        );

        Message resp = Message.ok(MessageType.GET_RESULT, request.getRequestId(), dto);
        resp.setAttemptId(attemptId);
        resp.setServerTime(now);
        return resp;
    }
}
