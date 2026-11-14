package vn.edu.ptit.exam.server.handler;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.common.config.Config;
import vn.edu.ptit.exam.common.dto.AuthToken;
import vn.edu.ptit.exam.common.dto.Message;
import vn.edu.ptit.exam.common.dto.QuestionDto;
import vn.edu.ptit.exam.common.dto.QuestionListResponse;
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

import java.util.HashMap;
import java.util.Map;

/**
 * Handles question operations for lecturers and administrators:
 * QUESTION_LIST, QUESTION_CREATE, QUESTION_UPDATE, QUESTION_DELETE.
 * Strictly verifies role (LECTURER or ADMIN), protects active exams (423), and handles version conflicts (409).
 */
public class QuestionHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(QuestionHandler.class);

    private final QuestionDao questionDao;
    private final SyncLogWriter syncLogWriter;
    private final String hmacSecretKey;

    public QuestionHandler(QuestionDao questionDao, SyncLogWriter syncLogWriter) {
        this.questionDao = questionDao;
        this.syncLogWriter = syncLogWriter;
        this.hmacSecretKey = Config.getInstance().getHmacSecretKey();
    }

    @Override
    public Message handle(Message request, SocketContext ctx) throws Exception {
        String reqTypeStr = request.getType();
        MessageType type = MessageType.valueOf(reqTypeStr);

        // 1. Authenticate Token
        String tokenStr = request.getToken();
        AuthToken token = HmacUtil.parseAndVerifyToken(tokenStr, hmacSecretKey);
        if (token == null) {
            return Message.error(type, request.getRequestId(), ErrorCode.UNAUTHENTICATED,
                    "Phiên đăng nhập không hợp lệ hoặc đã hết hạn");
        }

        // 2. Authorize Role (Lecturer or Admin only)
        if (token.getRole() != UserRole.LECTURER && token.getRole() != UserRole.ADMIN) {
            return Message.error(type, request.getRequestId(), ErrorCode.FORBIDDEN,
                    "Chỉ giảng viên hoặc quản trị viên mới có quyền thao tác ngân hàng câu hỏi");
        }

        long serverTime = System.currentTimeMillis();

        try {
            switch (type) {
                case QUESTION_LIST:
                    return handleList(request);
                case QUESTION_CREATE:
                    return handleCreate(request, token);
                case QUESTION_UPDATE:
                    return handleUpdate(request, serverTime);
                case QUESTION_DELETE:
                    return handleDelete(request, serverTime);
                default:
                    return Message.error(type, request.getRequestId(), ErrorCode.BAD_REQUEST, "Loại thông điệp không được hỗ trợ");
            }
        } catch (VersionConflictException e) {
            log.warn("Version conflict for request {}: {}", request.getRequestId(), e.getMessage());
            return Message.errorWithPayload(type, request.getRequestId(), ErrorCode.VERSION_CONFLICT, e.getMessage(), e.getPayload());
        } catch (ResourceLockedException e) {
            log.warn("Resource locked for request {}: {}", request.getRequestId(), e.getMessage());
            return Message.error(type, request.getRequestId(), ErrorCode.IN_USE_BY_ACTIVE_EXAM, e.getMessage());
        } catch (ExamServerException e) {
            log.warn("Business error handling {}: {}", type, e.getMessage());
            return Message.error(type, request.getRequestId(), e.getErrorCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Error processing question operation {}: {}", type, e.getMessage(), e);
            return Message.error(type, request.getRequestId(), ErrorCode.INTERNAL, "Lỗi nội bộ: " + e.getMessage());
        }
    }

    private Message handleList(Message request) throws Exception {
        String keyword = null;
        String subject = null;
        Integer chapter = null;
        String difficulty = null;
        int page = 1;
        int pageSize = 10;

        if (request.getPayload() != null) {
            JsonNode node = JsonUtil.readTree(JsonUtil.toJson(request.getPayload()));
            if (node.has("keyword") && !node.get("keyword").isNull()) {
                keyword = node.get("keyword").asText().trim();
            }
            if (node.has("subject") && !node.get("subject").isNull()) {
                subject = node.get("subject").asText().trim();
            }
            if (node.has("chapter") && !node.get("chapter").isNull()) {
                chapter = node.get("chapter").asInt();
            }
            if (node.has("difficulty") && !node.get("difficulty").isNull()) {
                difficulty = node.get("difficulty").asText().trim();
            }
            if (node.has("page") && !node.get("page").isNull()) {
                page = node.get("page").asInt(1);
            }
            if (node.has("pageSize") && !node.get("pageSize").isNull()) {
                pageSize = node.get("pageSize").asInt(10);
            }
        }

        QuestionListResponse response = questionDao.listQuestions(keyword, subject, chapter, difficulty, page, pageSize);
        return Message.ok(MessageType.QUESTION_LIST, request.getRequestId(), response);
    }

    private Message handleCreate(Message request, AuthToken token) throws Exception {
        if (request.getPayload() == null) {
            return Message.error(MessageType.QUESTION_CREATE, request.getRequestId(), ErrorCode.BAD_REQUEST, "Thiếu dữ liệu câu hỏi");
        }
        QuestionDto dto = JsonUtil.convertValue(request.getPayload(), QuestionDto.class);
        QuestionDto created = questionDao.createQuestion(dto, token.getUserId(), syncLogWriter);
        return Message.ok(MessageType.QUESTION_CREATE, request.getRequestId(), created);
    }

    private Message handleUpdate(Message request, long serverTime) throws Exception {
        if (request.getPayload() == null) {
            return Message.error(MessageType.QUESTION_UPDATE, request.getRequestId(), ErrorCode.BAD_REQUEST, "Thiếu dữ liệu câu hỏi cần sửa");
        }
        QuestionDto dto = JsonUtil.convertValue(request.getPayload(), QuestionDto.class);
        int baseVersion = dto.getVersion() != null ? dto.getVersion() : 1;

        // Check if baseVersion was explicitly specified in payload
        JsonNode node = JsonUtil.readTree(JsonUtil.toJson(request.getPayload()));
        if (node.has("baseVersion")) {
            baseVersion = node.get("baseVersion").asInt();
        }

        QuestionDto updated = questionDao.updateQuestion(dto, baseVersion, syncLogWriter, serverTime);
        return Message.ok(MessageType.QUESTION_UPDATE, request.getRequestId(), updated);
    }

    private Message handleDelete(Message request, long serverTime) throws Exception {
        if (request.getPayload() == null) {
            return Message.error(MessageType.QUESTION_DELETE, request.getRequestId(), ErrorCode.BAD_REQUEST, "Thiếu dữ liệu câu hỏi cần xóa");
        }
        JsonNode node = JsonUtil.readTree(JsonUtil.toJson(request.getPayload()));
        String questionId = node.has("questionId") ? node.get("questionId").asText().trim() :
                           (node.has("id") ? node.get("id").asText().trim() : null);
        int baseVersion = node.has("baseVersion") ? node.get("baseVersion").asInt() :
                         (node.has("version") ? node.get("version").asInt() : 1);

        if (questionId == null || questionId.isEmpty()) {
            return Message.error(MessageType.QUESTION_DELETE, request.getRequestId(), ErrorCode.BAD_REQUEST, "Thiếu mã câu hỏi");
        }

        questionDao.deleteQuestion(questionId, baseVersion, syncLogWriter, serverTime);
        Map<String, Object> result = new HashMap<>();
        result.put("questionId", questionId);
        result.put("success", true);
        return Message.ok(MessageType.QUESTION_DELETE, request.getRequestId(), result);
    }
}
