package vn.edu.ptit.exam.server.handler;

import com.fasterxml.jackson.databind.JsonNode;
import vn.edu.ptit.exam.common.dto.Message;
import vn.edu.ptit.exam.common.enums.MessageType;
import vn.edu.ptit.exam.common.util.JsonUtil;
import vn.edu.ptit.exam.server.net.ExamServer;
import vn.edu.ptit.exam.server.net.SocketContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles PING messages. Always returns PONG with serverTime and load info.
 * Also extracts downServers list from Load Balancer heartbeat (Luồng 8 Bước 9).
 */
public class PingHandler implements MessageHandler {

    private final ExamServer server;

    public PingHandler(ExamServer server) {
        this.server = server;
    }

    @Override
    public Message handle(Message request, SocketContext ctx) {
        // Extract downServers sent by Load Balancer in PING
        if (request.getPayload() != null) {
            try {
                JsonNode node = JsonUtil.readTree(JsonUtil.toJson(request.getPayload()));
                if (node.has("downServers") && node.get("downServers").isArray()) {
                    List<String> downList = new ArrayList<>();
                    for (JsonNode item : node.get("downServers")) {
                        downList.add(item.asText());
                    }
                    server.updateDownServers(downList);
                }
            } catch (Exception ignored) {
            }
        }

        Message pong = new Message(MessageType.PONG.name(), request.getRequestId());
        pong.setStatus("OK");
        pong.setCode(200);
        pong.setServerTime(System.currentTimeMillis());

        Map<String, Object> payload = new HashMap<>();
        payload.put("serverId", server.getServerId());
        payload.put("serverIndex", server.getServerIndex());
        payload.put("activeConnections", server.getActiveConnectionCount());
        payload.put("activeBusinessThreads", server.getActiveBusinessThreadCount());
        payload.put("businessQueueSize", server.getBusinessQueueSize());
        payload.put("downServers", new ArrayList<>(server.getDownServerIds()));
        pong.setPayload(payload);

        return pong;
    }
}
