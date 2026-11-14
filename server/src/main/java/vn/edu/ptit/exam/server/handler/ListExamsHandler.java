package vn.edu.ptit.exam.server.handler;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.common.config.Config;
import vn.edu.ptit.exam.common.dto.AuthToken;
import vn.edu.ptit.exam.common.dto.ExamListResponse;
import vn.edu.ptit.exam.common.dto.Message;
import vn.edu.ptit.exam.common.enums.ErrorCode;
import vn.edu.ptit.exam.common.enums.MessageType;
import vn.edu.ptit.exam.common.enums.UserRole;
import vn.edu.ptit.exam.common.util.HmacUtil;
import vn.edu.ptit.exam.common.util.JsonUtil;
import vn.edu.ptit.exam.server.dao.ExamDao;
import vn.edu.ptit.exam.server.net.SocketContext;

/**
 * Handles LIST_EXAMS requests from authenticated students.
 */
public class ListExamsHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(ListExamsHandler.class);
    private final ExamDao examDao;
    private final String hmacSecretKey;

    public ListExamsHandler(ExamDao examDao) {
        this.examDao = examDao;
        this.hmacSecretKey = Config.getInstance().getHmacSecretKey();
    }

    @Override
    public Message handle(Message request, SocketContext ctx) throws Exception {
        // 1. Verify token
        String tokenStr = request.getToken();
        AuthToken token = HmacUtil.parseAndVerifyToken(tokenStr, hmacSecretKey);

        if (token == null) {
            return Message.error(MessageType.LIST_EXAMS, request.getRequestId(), ErrorCode.UNAUTHENTICATED,
                    "Phiên đăng nhập không hợp lệ hoặc đã hết hạn");
        }

        // 2. Extract parameters from payload
        String keyword = null;
        String statusFilter = null;
        int page = 1;
        int pageSize = 10;

        if (request.getPayload() != null) {
            JsonNode node = JsonUtil.readTree(JsonUtil.toJson(request.getPayload()));
            if (node.has("keyword") && !node.get("keyword").isNull()) {
                keyword = node.get("keyword").asText().trim();
            }
            if (node.has("status") && !node.get("status").isNull()) {
                statusFilter = node.get("status").asText().trim();
            }
            if (node.has("page") && !node.get("page").isNull()) {
                page = node.get("page").asInt(1);
            }
            if (node.has("pageSize") && !node.get("pageSize").isNull()) {
                pageSize = node.get("pageSize").asInt(10);
            }
        }

        long serverTime = System.currentTimeMillis();

        // 3. Query exams
        ExamListResponse responseData = examDao.listExamsForStudent(token.getUserId(), keyword, statusFilter, page, pageSize, serverTime);

        Message response = Message.ok(MessageType.LIST_EXAMS, request.getRequestId(), responseData);
        response.setServerTime(serverTime);
        return response;
    }
}
