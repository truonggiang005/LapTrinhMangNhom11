package vn.edu.ptit.exam.server.handler;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.common.config.Config;
import vn.edu.ptit.exam.common.dto.AuthToken;
import vn.edu.ptit.exam.common.dto.Message;
import vn.edu.ptit.exam.common.enums.ErrorCode;
import vn.edu.ptit.exam.common.enums.MessageType;
import vn.edu.ptit.exam.common.util.HmacUtil;
import vn.edu.ptit.exam.common.util.JsonUtil;
import vn.edu.ptit.exam.server.dao.AttemptDao;
import vn.edu.ptit.exam.server.net.SocketContext;

import java.util.List;
import java.util.Map;

/**
 * Handles LIST_HISTORY requests for student's exam history.
 */
public class ListHistoryHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(ListHistoryHandler.class);

    private final AttemptDao attemptDao;
    private final String hmacSecretKey;

    public ListHistoryHandler(AttemptDao attemptDao) {
        this.attemptDao = attemptDao;
        this.hmacSecretKey = Config.getInstance().getHmacSecretKey();
    }

    @Override
    public Message handle(Message request, SocketContext ctx) throws Exception {
        AuthToken token = HmacUtil.parseAndVerifyToken(request.getToken(), hmacSecretKey);
        if (token == null) {
            return Message.error(MessageType.LIST_HISTORY, request.getRequestId(), ErrorCode.UNAUTHENTICATED);
        }

        int page = 1;
        int pageSize = 10;
        if (request.getPayload() != null) {
            JsonNode node = JsonUtil.readTree(JsonUtil.toJson(request.getPayload()));
            if (node.has("page")) page = node.get("page").asInt(1);
            if (node.has("pageSize")) pageSize = node.get("pageSize").asInt(10);
        }

        List<Map<String, Object>> history = attemptDao.listHistoryForStudent(token.getUserId(), page, pageSize);

        Message resp = Message.ok(MessageType.LIST_HISTORY, request.getRequestId(), history);
        resp.setServerTime(System.currentTimeMillis());
        return resp;
    }
}
