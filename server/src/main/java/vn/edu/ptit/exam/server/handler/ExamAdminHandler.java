package vn.edu.ptit.exam.server.handler;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.common.config.Config;
import vn.edu.ptit.exam.common.dto.*;
import vn.edu.ptit.exam.common.enums.ErrorCode;
import vn.edu.ptit.exam.common.enums.MessageType;
import vn.edu.ptit.exam.common.enums.UserRole;
import vn.edu.ptit.exam.common.util.HmacUtil;
import vn.edu.ptit.exam.common.util.JsonUtil;
import vn.edu.ptit.exam.server.dao.ExamDao;
import vn.edu.ptit.exam.server.dao.QuestionDao;
import vn.edu.ptit.exam.server.exception.ExamServerException;
import vn.edu.ptit.exam.server.net.SocketContext;
import vn.edu.ptit.exam.server.sync.SyncLogWriter;
import vn.edu.ptit.exam.server.sync.SyncManager;

import java.util.Map;

/**
 * Handles Lecturer & Administrator exam management messages:
 * EXAM_CREATE, EXAM_MONITOR, GET_SCORES, GET_STATS.
 * Also supports querying exam replication readiness across the 4 servers.
 */
public class ExamAdminHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(ExamAdminHandler.class);

    private final ExamDao examDao;
    private final QuestionDao questionDao;
    private final SyncLogWriter syncLogWriter;
    private final SyncManager syncManager;
    private final String localServerId;
    private final String hmacSecretKey;

    public ExamAdminHandler(ExamDao examDao, QuestionDao questionDao,
                            SyncLogWriter syncLogWriter, SyncManager syncManager, String localServerId) {
        this.examDao = examDao;
        this.questionDao = questionDao;
        this.syncLogWriter = syncLogWriter;
        this.syncManager = syncManager;
        this.localServerId = localServerId;
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

        // 2. Authorize Role (Lecturer or Admin only)
        if (token.getRole() != UserRole.LECTURER && token.getRole() != UserRole.ADMIN) {
            return Message.error(type, request.getRequestId(), ErrorCode.FORBIDDEN,
                    "Chỉ giảng viên hoặc quản trị viên mới có quyền truy cập ca thi này");
        }

        try {
            switch (type) {
                case EXAM_CREATE:
                    return handleCreateExam(request, token);
                case EXAM_MONITOR:
                    return handleExamMonitor(request);
                case GET_SCORES:
                    return handleGetScores(request);
                case GET_STATS:
                    return handleGetStats(request);
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

    private Message handleCreateExam(Message request, AuthToken token) throws Exception {
        if (request.getPayload() == null) {
            return Message.error(MessageType.EXAM_CREATE, request.getRequestId(), ErrorCode.BAD_REQUEST, "Thiếu dữ liệu tạo ca thi");
        }
        CreateExamRequest createReq = JsonUtil.convertValue(request.getPayload(), CreateExamRequest.class);
        ExamDto created = examDao.createExamPackage(createReq, token.getUserId(), syncLogWriter, questionDao);
        return Message.ok(MessageType.EXAM_CREATE, request.getRequestId(), created);
    }

    private Message handleExamMonitor(Message request) throws Exception {
        if (request.getPayload() == null) {
            return Message.error(MessageType.EXAM_MONITOR, request.getRequestId(), ErrorCode.BAD_REQUEST, "Thiếu mã ca thi");
        }
        JsonNode node = JsonUtil.readTree(JsonUtil.toJson(request.getPayload()));
        String examId = node.has("examId") ? node.get("examId").asText().trim() : null;
        if (examId == null || examId.isEmpty()) {
            return Message.error(MessageType.EXAM_MONITOR, request.getRequestId(), ErrorCode.BAD_REQUEST, "Thiếu mã ca thi");
        }

        // Check if readiness check is requested
        if (node.has("checkReadiness") && node.get("checkReadiness").asBoolean()) {
            ExamReadinessDto readiness = examDao.getExamReadiness(examId,
                    syncManager != null ? syncManager.getSyncPeerStateDao() : null, localServerId);
            return Message.ok(MessageType.EXAM_MONITOR, request.getRequestId(), readiness);
        }

        ExamMonitorDto monitor = examDao.getExamMonitor(examId);
        return Message.ok(MessageType.EXAM_MONITOR, request.getRequestId(), monitor);
    }

    private Message handleGetScores(Message request) throws Exception {
        if (request.getPayload() == null) {
            return Message.error(MessageType.GET_SCORES, request.getRequestId(), ErrorCode.BAD_REQUEST, "Thiếu mã ca thi");
        }
        JsonNode node = JsonUtil.readTree(JsonUtil.toJson(request.getPayload()));
        String examId = node.has("examId") ? node.get("examId").asText().trim() : null;
        if (examId == null || examId.isEmpty()) {
            return Message.error(MessageType.GET_SCORES, request.getRequestId(), ErrorCode.BAD_REQUEST, "Thiếu mã ca thi");
        }

        String keyword = node.has("keyword") && !node.get("keyword").isNull() ? node.get("keyword").asText().trim() : null;
        int page = node.has("page") ? node.get("page").asInt(1) : 1;
        int pageSize = node.has("pageSize") ? node.get("pageSize").asInt(200) : 200;
        String sortBy = node.has("sort") ? node.get("sort").asText("studentCode") : "studentCode";

        ScoreListResponse response = examDao.getScores(examId, keyword, page, pageSize, sortBy);
        return Message.ok(MessageType.GET_SCORES, request.getRequestId(), response);
    }

    private Message handleGetStats(Message request) throws Exception {
        if (request.getPayload() == null) {
            return Message.error(MessageType.GET_STATS, request.getRequestId(), ErrorCode.BAD_REQUEST, "Thiếu mã ca thi");
        }
        JsonNode node = JsonUtil.readTree(JsonUtil.toJson(request.getPayload()));
        String examId = node.has("examId") ? node.get("examId").asText().trim() : null;
        if (examId == null || examId.isEmpty()) {
            return Message.error(MessageType.GET_STATS, request.getRequestId(), ErrorCode.BAD_REQUEST, "Thiếu mã ca thi");
        }

        ExamStatsDto stats = examDao.getStats(examId);
        return Message.ok(MessageType.GET_STATS, request.getRequestId(), stats);
    }
}
