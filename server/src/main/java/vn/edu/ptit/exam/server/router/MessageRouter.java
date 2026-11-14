package vn.edu.ptit.exam.server.router;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.common.dto.Message;
import vn.edu.ptit.exam.common.enums.ErrorCode;
import vn.edu.ptit.exam.common.enums.MessageType;
import vn.edu.ptit.exam.server.handler.MessageHandler;
import vn.edu.ptit.exam.server.net.SocketContext;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Message router mapping message types to their respective handlers.
 * Easily extensible for future stages (START_EXAM, SAVE_ANSWERS, SUBMIT, etc.).
 */
public class MessageRouter {

    private static final Logger log = LoggerFactory.getLogger(MessageRouter.class);

    private final Map<String, MessageHandler> handlers = new ConcurrentHashMap<>();

    public void registerHandler(String messageType, MessageHandler handler) {
        handlers.put(messageType.toUpperCase(), handler);
        log.debug("Registered handler for type: {}", messageType);
    }

    public void registerHandler(MessageType messageType, MessageHandler handler) {
        registerHandler(messageType.name(), handler);
    }

    public Message route(Message request, SocketContext ctx) {
        if (request == null || request.getType() == null) {
            return Message.error("UNKNOWN", "NO_ID", ErrorCode.BAD_REQUEST, "Thông điệp rỗng hoặc thiếu trường 'type'");
        }

        String type = request.getType().toUpperCase();
        MessageHandler handler = handlers.get(type);

        if (handler == null) {
            log.warn("No handler found for message type: {}", type);
            return Message.error(type, request.getRequestId(), ErrorCode.BAD_REQUEST,
                    "Không hỗ trợ loại thông điệp: " + type);
        }

        try {
            return handler.handle(request, ctx);
        } catch (Exception e) {
            log.error("Error executing handler for type [{}]: {}", type, e.getMessage(), e);
            return Message.error(type, request.getRequestId(), ErrorCode.INTERNAL,
                    "Lỗi hệ thống khi xử lý yêu cầu: " + e.getMessage());
        }
    }
}
