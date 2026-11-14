package vn.edu.ptit.exam.server.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.common.config.Config;
import vn.edu.ptit.exam.common.dto.AuthToken;
import vn.edu.ptit.exam.common.dto.BatchUserResultDto;
import vn.edu.ptit.exam.common.dto.Message;
import vn.edu.ptit.exam.common.dto.UserDto;
import vn.edu.ptit.exam.common.dto.UserListResponse;
import vn.edu.ptit.exam.common.enums.ErrorCode;
import vn.edu.ptit.exam.common.enums.MessageType;
import vn.edu.ptit.exam.common.enums.UserRole;
import vn.edu.ptit.exam.common.util.HmacUtil;
import vn.edu.ptit.exam.common.util.JsonUtil;
import vn.edu.ptit.exam.server.dao.UserDao;
import vn.edu.ptit.exam.server.exception.ExamServerException;
import vn.edu.ptit.exam.server.net.SocketContext;
import vn.edu.ptit.exam.server.sync.SyncLogWriter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles Administrator account management messages:
 * USER_CREATE, USER_UPDATE, USER_RESET_PASSWORD, USER_LOCK, USER_BATCH.
 * Strictly checks ADMIN role and transactional sync logging.
 */
public class UserAdminHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(UserAdminHandler.class);

    private final UserDao userDao;
    private final SyncLogWriter syncLogWriter;
    private final String hmacSecretKey;

    public UserAdminHandler(UserDao userDao, SyncLogWriter syncLogWriter) {
        this.userDao = userDao;
        this.syncLogWriter = syncLogWriter;
        this.hmacSecretKey = Config.getInstance().getHmacSecretKey();
    }

    @Override
    public Message handle(Message request, SocketContext ctx) throws Exception {
        String typeStr = request.getType();
        MessageType type = MessageType.valueOf(typeStr);

        // 1. Authenticate Token
        String tokenStr = request.getToken();
        AuthToken token = HmacUtil.parseAndVerifyToken(tokenStr, hmacSecretKey);
        if (token == null) {
            return Message.error(type, request.getRequestId(), ErrorCode.UNAUTHENTICATED,
                    "Phiên đăng nhập không hợp lệ hoặc đã hết hạn");
        }

        // 2. Authorize Role (Admin only)
        if (token.getRole() != UserRole.ADMIN) {
            return Message.error(type, request.getRequestId(), ErrorCode.FORBIDDEN,
                    "Chỉ quản trị viên mới có quyền quản lý người dùng");
        }

        try {
            switch (type) {
                case USER_LIST:
                    return handleList(request);
                case USER_CREATE:
                    return handleCreate(request);
                case USER_UPDATE:
                    return handleUpdate(request);
                case USER_RESET_PASSWORD:
                    return handleResetPassword(request);
                case USER_LOCK:
                    return handleLock(request);
                case USER_BATCH:
                    return handleBatch(request);
                default:
                    return Message.error(type, request.getRequestId(), ErrorCode.BAD_REQUEST, "Loại thông điệp không được hỗ trợ");
            }
        } catch (ExamServerException e) {
            log.warn("Business error handling {}: {}", type, e.getMessage());
            return Message.error(type, request.getRequestId(), e.getErrorCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Internal error handling {}: {}", type, e.getMessage(), e);
            return Message.error(type, request.getRequestId(), ErrorCode.INTERNAL, "Lỗi nội bộ: " + e.getMessage());
        }
    }

    private Message handleList(Message request) throws Exception {
        String keyword = null;
        String role = null;
        int page = 1;
        int pageSize = 50;
        if (request.getPayload() != null) {
            JsonNode node = JsonUtil.readTree(JsonUtil.toJson(request.getPayload()));
            if (node.has("keyword") && !node.get("keyword").isNull()) keyword = node.get("keyword").asText();
            if (node.has("role") && !node.get("role").isNull()) role = node.get("role").asText();
            if (node.has("page")) page = node.get("page").asInt(1);
            if (node.has("pageSize")) pageSize = node.get("pageSize").asInt(50);
        }
        UserListResponse resp = userDao.listUsers(keyword, role, page, pageSize);
        return Message.ok(MessageType.USER_LIST, request.getRequestId(), resp);
    }

    private Message handleCreate(Message request) throws Exception {
        if (request.getPayload() == null) {
            return Message.error(MessageType.USER_CREATE, request.getRequestId(), ErrorCode.BAD_REQUEST, "Thiếu dữ liệu tài khoản");
        }
        UserDto dto = JsonUtil.convertValue(request.getPayload(), UserDto.class);
        UserDto created = userDao.createUser(dto, syncLogWriter);
        return Message.ok(MessageType.USER_CREATE, request.getRequestId(), created);
    }

    private Message handleUpdate(Message request) throws Exception {
        if (request.getPayload() == null) {
            return Message.error(MessageType.USER_UPDATE, request.getRequestId(), ErrorCode.BAD_REQUEST, "Thiếu dữ liệu sửa tài khoản");
        }
        UserDto dto = JsonUtil.convertValue(request.getPayload(), UserDto.class);
        UserDto updated = userDao.updateUser(dto, syncLogWriter);
        return Message.ok(MessageType.USER_UPDATE, request.getRequestId(), updated);
    }

    private Message handleResetPassword(Message request) throws Exception {
        if (request.getPayload() == null) {
            return Message.error(MessageType.USER_RESET_PASSWORD, request.getRequestId(), ErrorCode.BAD_REQUEST, "Thiếu thông tin đặt lại mật khẩu");
        }
        JsonNode node = JsonUtil.readTree(JsonUtil.toJson(request.getPayload()));
        String userId = node.has("userId") ? node.get("userId").asText().trim() : null;
        String newPassword = node.has("newPassword") ? node.get("newPassword").asText().trim() : null;

        if (userId == null || newPassword == null || newPassword.isEmpty()) {
            return Message.error(MessageType.USER_RESET_PASSWORD, request.getRequestId(), ErrorCode.BAD_REQUEST, "Thiếu mã tài khoản hoặc mật khẩu mới");
        }

        userDao.resetPassword(userId, newPassword, syncLogWriter);
        Map<String, Object> res = new HashMap<>();
        res.put("userId", userId);
        res.put("success", true);
        return Message.ok(MessageType.USER_RESET_PASSWORD, request.getRequestId(), res);
    }

    private Message handleLock(Message request) throws Exception {
        if (request.getPayload() == null) {
            return Message.error(MessageType.USER_LOCK, request.getRequestId(), ErrorCode.BAD_REQUEST, "Thiếu thông tin khóa tài khoản");
        }
        JsonNode node = JsonUtil.readTree(JsonUtil.toJson(request.getPayload()));
        String userId = node.has("userId") ? node.get("userId").asText().trim() : null;
        boolean locked = node.has("locked") ? node.get("locked").asBoolean(true) : true;

        if (userId == null || userId.isEmpty()) {
            return Message.error(MessageType.USER_LOCK, request.getRequestId(), ErrorCode.BAD_REQUEST, "Thiếu mã tài khoản");
        }

        userDao.setUserLocked(userId, locked, syncLogWriter);
        Map<String, Object> res = new HashMap<>();
        res.put("userId", userId);
        res.put("locked", locked);
        res.put("success", true);
        return Message.ok(MessageType.USER_LOCK, request.getRequestId(), res);
    }

    private Message handleBatch(Message request) throws Exception {
        if (request.getPayload() == null) {
            return Message.error(MessageType.USER_BATCH, request.getRequestId(), ErrorCode.BAD_REQUEST, "Thiếu danh sách tài khoản");
        }
        List<UserDto> users;
        JsonNode node = JsonUtil.readTree(JsonUtil.toJson(request.getPayload()));
        if (node.isArray()) {
            users = JsonUtil.convertValue(node, new TypeReference<List<UserDto>>() {});
        } else if (node.has("users") && node.get("users").isArray()) {
            users = JsonUtil.convertValue(node.get("users"), new TypeReference<List<UserDto>>() {});
        } else {
            return Message.error(MessageType.USER_BATCH, request.getRequestId(), ErrorCode.BAD_REQUEST, "Dữ liệu danh sách tài khoản không hợp lệ");
        }

        BatchUserResultDto result = userDao.importUsersBatch(users, syncLogWriter);
        return Message.ok(MessageType.USER_BATCH, request.getRequestId(), result);
    }
}
