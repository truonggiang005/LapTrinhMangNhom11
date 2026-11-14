package vn.edu.ptit.exam.server.handler;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.common.config.Config;
import vn.edu.ptit.exam.common.dto.AuthToken;
import vn.edu.ptit.exam.common.dto.Message;
import vn.edu.ptit.exam.common.dto.RetakeRequestDto;
import vn.edu.ptit.exam.common.enums.ErrorCode;
import vn.edu.ptit.exam.common.enums.MessageType;
import vn.edu.ptit.exam.common.enums.UserRole;
import vn.edu.ptit.exam.common.util.HmacUtil;
import vn.edu.ptit.exam.common.util.JsonUtil;
import vn.edu.ptit.exam.server.dao.ExamDao;
import vn.edu.ptit.exam.server.exception.ExamServerException;
import vn.edu.ptit.exam.server.net.SocketContext;
import vn.edu.ptit.exam.server.sync.SyncLogWriter;

import java.util.Collections;
import java.util.List;

/**
 * Xử lý các yêu cầu xin thi lại từ Thí sinh và phê duyệt từ Giảng viên.
 */
public class RetakeHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(RetakeHandler.class);

    private final ExamDao examDao;
    private final SyncLogWriter syncLogWriter;
    private final String hmacSecretKey;

    public RetakeHandler(ExamDao examDao, SyncLogWriter syncLogWriter) {
        this.examDao = examDao;
        this.syncLogWriter = syncLogWriter;
        this.hmacSecretKey = Config.getInstance().getHmacSecretKey();
    }

    @Override
    public Message handle(Message request, SocketContext ctx) throws Exception {
        String typeStr = request.getType();
        MessageType type = MessageType.valueOf(typeStr);

        // 1. Xác thực Token
        String tokenStr = request.getToken();
        AuthToken token = HmacUtil.parseAndVerifyToken(tokenStr, hmacSecretKey);
        if (token == null) {
            return Message.error(type, request.getRequestId(), ErrorCode.UNAUTHENTICATED,
                    "Phiên đăng nhập không hợp lệ hoặc đã hết hạn");
        }

        try {
            switch (type) {
                case EXAM_RETAKE_REQUEST:
                    return handleRetakeRequest(request, token);
                case EXAM_RETAKE_LIST:
                    return handleRetakeList(request, token);
                case EXAM_RETAKE_DECIDE:
                    return handleRetakeDecide(request, token);
                default:
                    return Message.error(type, request.getRequestId(), ErrorCode.BAD_REQUEST, "Loại thông điệp không được hỗ trợ");
            }
        } catch (ExamServerException e) {
            log.warn("Lỗi nghiệp vụ thi lại {}: {}", type, e.getMessage());
            return Message.error(type, request.getRequestId(), e.getErrorCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Lỗi máy chủ xử lý thi lại {}: {}", type, e.getMessage(), e);
            return Message.error(type, request.getRequestId(), ErrorCode.INTERNAL, "Lỗi máy chủ: " + e.getMessage());
        }
    }

    private Message handleRetakeRequest(Message request, AuthToken token) throws Exception {
        if (request.getPayload() == null) {
            return Message.error(MessageType.EXAM_RETAKE_REQUEST, request.getRequestId(),
                    ErrorCode.BAD_REQUEST, "Thiếu thông tin gửi yêu cầu thi lại");
        }

        JsonNode node = JsonUtil.readTree(JsonUtil.toJson(request.getPayload()));
        String examId = node.has("examId") ? node.get("examId").asText().trim() : null;
        String reason = node.has("reason") ? node.get("reason").asText().trim() : "Xin thi lại do sự cố";

        if (examId == null || examId.isEmpty()) {
            return Message.error(MessageType.EXAM_RETAKE_REQUEST, request.getRequestId(),
                    ErrorCode.BAD_REQUEST, "Mã ca thi không được để trống");
        }

        RetakeRequestDto result = examDao.submitRetakeRequest(examId, token.getUserId(), reason, syncLogWriter);
        return Message.ok(MessageType.EXAM_RETAKE_REQUEST, request.getRequestId(), result);
    }

    private Message handleRetakeList(Message request, AuthToken token) throws Exception {
        if (token.getRole() != UserRole.LECTURER && token.getRole() != UserRole.ADMIN) {
            return Message.error(MessageType.EXAM_RETAKE_LIST, request.getRequestId(),
                    ErrorCode.FORBIDDEN, "Chỉ giảng viên hoặc quản trị viên mới có quyền xem danh sách yêu cầu thi lại");
        }

        String examId = null;
        String status = null;
        if (request.getPayload() != null) {
            JsonNode node = JsonUtil.readTree(JsonUtil.toJson(request.getPayload()));
            if (node.has("examId") && !node.get("examId").isNull()) {
                examId = node.get("examId").asText().trim();
            }
            if (node.has("status") && !node.get("status").isNull()) {
                status = node.get("status").asText().trim();
            }
        }

        List<RetakeRequestDto> list = examDao.listRetakeRequests(examId, status);
        return Message.ok(MessageType.EXAM_RETAKE_LIST, request.getRequestId(), list);
    }

    private Message handleRetakeDecide(Message request, AuthToken token) throws Exception {
        if (token.getRole() != UserRole.LECTURER && token.getRole() != UserRole.ADMIN) {
            return Message.error(MessageType.EXAM_RETAKE_DECIDE, request.getRequestId(),
                    ErrorCode.FORBIDDEN, "Chỉ giảng viên hoặc quản trị viên mới có quyền phê duyệt thi lại");
        }

        if (request.getPayload() == null) {
            return Message.error(MessageType.EXAM_RETAKE_DECIDE, request.getRequestId(),
                    ErrorCode.BAD_REQUEST, "Thiếu thông tin phê duyệt");
        }

        JsonNode node = JsonUtil.readTree(JsonUtil.toJson(request.getPayload()));
        String requestId = node.has("requestId") ? node.get("requestId").asText().trim() : null;
        String action = node.has("action") ? node.get("action").asText().trim() : null;

        if (requestId == null || requestId.isEmpty()) {
            return Message.error(MessageType.EXAM_RETAKE_DECIDE, request.getRequestId(),
                    ErrorCode.BAD_REQUEST, "Mã yêu cầu không được để trống");
        }
        if (action == null || action.isEmpty()) {
            return Message.error(MessageType.EXAM_RETAKE_DECIDE, request.getRequestId(),
                    ErrorCode.BAD_REQUEST, "Hành động phê duyệt không được để trống");
        }

        examDao.decideRetakeRequest(requestId, action, token.getUsername(), syncLogWriter);
        return Message.ok(MessageType.EXAM_RETAKE_DECIDE, request.getRequestId(), Collections.singletonMap("success", true));
    }
}
