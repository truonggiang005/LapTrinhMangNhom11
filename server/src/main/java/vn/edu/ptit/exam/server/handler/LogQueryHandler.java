package vn.edu.ptit.exam.server.handler;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.common.config.Config;
import vn.edu.ptit.exam.common.dto.AuthToken;
import vn.edu.ptit.exam.common.dto.LogEntryDto;
import vn.edu.ptit.exam.common.dto.LogQueryResponse;
import vn.edu.ptit.exam.common.dto.Message;
import vn.edu.ptit.exam.common.enums.ErrorCode;
import vn.edu.ptit.exam.common.enums.MessageType;
import vn.edu.ptit.exam.common.enums.UserRole;
import vn.edu.ptit.exam.common.util.HmacUtil;
import vn.edu.ptit.exam.common.util.JsonUtil;
import vn.edu.ptit.exam.server.dao.AuditLogDao;
import vn.edu.ptit.exam.server.dao.SyncLogDao;
import vn.edu.ptit.exam.server.lb.DistributionLogger;
import vn.edu.ptit.exam.server.net.SocketContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles LOG_QUERY requests for administrators:
 * Queries DISTRIBUTION logs (Load Balancer), SYNC logs (SyncLogDao), or AUDIT logs (AuditLogDao).
 */
public class LogQueryHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(LogQueryHandler.class);

    private final AuditLogDao auditLogDao;
    private final SyncLogDao syncLogDao;
    private final java.util.function.Supplier<DistributionLogger> distributionLoggerSupplier;
    private final String hmacSecretKey;

    public LogQueryHandler(AuditLogDao auditLogDao, SyncLogDao syncLogDao, java.util.function.Supplier<DistributionLogger> distributionLoggerSupplier) {
        this.auditLogDao = auditLogDao;
        this.syncLogDao = syncLogDao;
        this.distributionLoggerSupplier = distributionLoggerSupplier;
        this.hmacSecretKey = Config.getInstance().getHmacSecretKey();
    }

    public LogQueryHandler(AuditLogDao auditLogDao, SyncLogDao syncLogDao, DistributionLogger distributionLogger) {
        this(auditLogDao, syncLogDao, () -> distributionLogger);
    }

    @Override
    public Message handle(Message request, SocketContext ctx) throws Exception {
        // 1. Authenticate Token
        String tokenStr = request.getToken();
        AuthToken token = HmacUtil.parseAndVerifyToken(tokenStr, hmacSecretKey);
        if (token == null) {
            return Message.error(MessageType.LOG_QUERY, request.getRequestId(), ErrorCode.UNAUTHENTICATED,
                    "Phiên đăng nhập không hợp lệ hoặc đã hết hạn");
        }

        // 2. Authorize Role (Admin only)
        if (token.getRole() != UserRole.ADMIN) {
            return Message.error(MessageType.LOG_QUERY, request.getRequestId(), ErrorCode.FORBIDDEN,
                    "Chỉ quản trị viên mới có quyền xem nhật ký hệ thống");
        }

        String logType = "AUDIT";
        String keyword = null;
        Long startTime = null;
        Long endTime = null;
        int page = 1;
        int pageSize = 50;

        if (request.getPayload() != null) {
            JsonNode node = JsonUtil.readTree(JsonUtil.toJson(request.getPayload()));
            if (node.has("logType") && !node.get("logType").isNull()) {
                logType = node.get("logType").asText().trim().toUpperCase();
            }
            if (node.has("keyword") && !node.get("keyword").isNull()) {
                keyword = node.get("keyword").asText().trim();
            }
            if (node.has("startTime") && !node.get("startTime").isNull()) {
                startTime = node.get("startTime").asLong();
            }
            if (node.has("endTime") && !node.get("endTime").isNull()) {
                endTime = node.get("endTime").asLong();
            }
            if (node.has("page") && !node.get("page").isNull()) {
                page = node.get("page").asInt(1);
            }
            if (node.has("pageSize") && !node.get("pageSize").isNull()) {
                pageSize = node.get("pageSize").asInt(50);
            }
        }

        try {
            LogQueryResponse response;
            DistributionLogger distributionLogger = distributionLoggerSupplier != null ? distributionLoggerSupplier.get() : null;
            if ("DISTRIBUTION".equalsIgnoreCase(logType)) {
                if (distributionLogger != null) {
                    List<DistributionLogger.LogEntry> rawEntries = distributionLogger.query(keyword, null, page, pageSize);
                    List<LogEntryDto> entries = new ArrayList<>();
                    for (DistributionLogger.LogEntry re : rawEntries) {
                        entries.add(new LogEntryDto(
                                re.getTimestamp(),
                                "DISTRIBUTION",
                                re.getRequestId(),
                                re.getUserId(),
                                re.getMessageType(),
                                String.valueOf(re.getTargetServerId()),
                                re.getStatus(),
                                "duration=" + re.getDurationMs() + "ms" + (re.getError() != null ? " error=" + re.getError() : "")
                        ));
                    }
                    response = new LogQueryResponse("DISTRIBUTION", distributionLogger.getTotalCount(), page, pageSize, entries);
                } else {
                    response = new LogQueryResponse("DISTRIBUTION", 0, page, pageSize, new ArrayList<>());
                }
            } else if ("SYNC".equalsIgnoreCase(logType)) {
                response = syncLogDao.queryLogs(keyword, startTime, endTime, page, pageSize);
            } else {
                // AUDIT
                response = auditLogDao.queryLogs(keyword, startTime, endTime, page, pageSize);
            }

            return Message.ok(MessageType.LOG_QUERY, request.getRequestId(), response);
        } catch (Exception e) {
            log.error("Failed to query {} logs: {}", logType, e.getMessage(), e);
            return Message.error(MessageType.LOG_QUERY, request.getRequestId(), ErrorCode.INTERNAL, "Lỗi truy vấn nhật ký: " + e.getMessage());
        }
    }
}
