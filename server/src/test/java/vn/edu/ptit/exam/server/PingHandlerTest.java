package vn.edu.ptit.exam.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.edu.ptit.exam.common.dto.Message;
import vn.edu.ptit.exam.common.enums.ErrorCode;
import vn.edu.ptit.exam.common.enums.MessageType;
import vn.edu.ptit.exam.server.handler.PingHandler;
import vn.edu.ptit.exam.server.net.ExamServer;
import vn.edu.ptit.exam.server.net.SocketContext;
import vn.edu.ptit.exam.server.router.MessageRouter;

import java.net.Socket;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PingHandlerTest {

    @Test
    @DisplayName("Should return PONG with serverTime, active connections, and load info")
    void testPingPong() {
        ExamServer mockServer = new ExamServer(1) {
            @Override
            public int getActiveConnectionCount() {
                return 42;
            }

            @Override
            public int getActiveBusinessThreadCount() {
                return 5;
            }

            @Override
            public int getBusinessQueueSize() {
                return 3;
            }
        };

        PingHandler pingHandler = new PingHandler(mockServer);
        SocketContext ctx = new SocketContext("test-ping", new Socket());

        Message ping = Message.request(MessageType.PING, "req-ping-1");
        Message pong = pingHandler.handle(ping, ctx);

        assertNotNull(pong);
        assertEquals("PONG", pong.getType());
        assertEquals("req-ping-1", pong.getRequestId());
        assertEquals(200, pong.getCode());
        assertNotNull(pong.getServerTime());

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) pong.getPayload();
        assertEquals("SERVER_1", payload.get("serverId"));
        assertEquals(1, payload.get("serverIndex"));
        assertEquals(42, payload.get("activeConnections"));
        assertEquals(5, payload.get("activeBusinessThreads"));
        assertEquals(3, payload.get("businessQueueSize"));
    }

    @Test
    @DisplayName("MessageRouter should return 400 BAD_REQUEST for unsupported message type")
    void testRouterUnknownType() {
        MessageRouter router = new MessageRouter();
        SocketContext ctx = new SocketContext("test-unknown", new Socket());

        Message req = new Message("UNKNOWN_OP", "req-unknown");
        Message resp = router.route(req, ctx);

        assertEquals("ERROR", resp.getStatus());
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), resp.getCode());
        assertTrue(resp.getMessage().contains("Không hỗ trợ loại thông điệp"));
    }
}
