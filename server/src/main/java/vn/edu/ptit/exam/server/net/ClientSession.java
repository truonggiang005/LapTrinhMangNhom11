package vn.edu.ptit.exam.server.net;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.common.dto.Message;
import vn.edu.ptit.exam.common.enums.ErrorCode;
import vn.edu.ptit.exam.common.enums.MessageType;
import vn.edu.ptit.exam.common.util.JsonUtil;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

/**
 * Handles communication with a single TCP connection.
 * Reads JSON lines, routes PING to the dedicated PING thread,
 * and submits business requests to the business thread pool.
 */
public class ClientSession implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ClientSession.class);

    private final Socket socket;
    private final ExamServer server;
    private final SocketContext context;
    private BufferedReader reader;
    private BufferedWriter writer;
    private volatile boolean running = true;

    public ClientSession(Socket socket, ExamServer server) {
        this.socket = socket;
        this.server = server;
        this.context = new SocketContext(UUID.randomUUID().toString(), socket);
    }

    @Override
    public void run() {
        server.onClientConnected(this);
        log.debug("Client connected: {} (Session ID: {})", context.getRemoteAddress(), context.getConnectionId());

        try {
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            String line;
            while (running && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                Message request;
                try {
                    request = JsonUtil.fromJson(line, Message.class);
                } catch (Exception e) {
                    log.warn("Malformed JSON received from {}: {}", context.getRemoteAddress(), line);
                    sendMessage(Message.error("UNKNOWN", "NO_ID", ErrorCode.BAD_REQUEST, "JSON không đúng định dạng"));
                    continue;
                }

                handleMessage(request);
            }
        } catch (IOException e) {
            if (running) {
                log.debug("Connection closed with {}: {}", context.getRemoteAddress(), e.getMessage());
            }
        } finally {
            close();
            server.onClientDisconnected(this);
        }
    }

    private void handleMessage(Message request) {
        String type = request.getType() != null ? request.getType().toUpperCase() : "UNKNOWN";

        // Invariant 7: PING is dispatched immediately to the dedicated PING thread
        if (MessageType.PING.name().equals(type)) {
            server.submitPingTask(() -> {
                Message pong = server.getRouter().route(request, context);
                sendMessage(pong);
            });
            return;
        }

        // Other business requests are dispatched to the business thread pool
        try {
            server.submitBusinessTask(() -> {
                Message response = server.getRouter().route(request, context);
                sendMessage(response);
            });
        } catch (RejectedExecutionException e) {
            log.warn("Business thread pool saturated. Returning 429 BUSY to {}", context.getRemoteAddress());
            sendMessage(Message.busy(type, request.getRequestId(), 3));
        }
    }

    public synchronized void sendMessage(Message message) {
        if (!running || writer == null) {
            return;
        }
        try {
            String json = JsonUtil.toJson(message);
            writer.write(json);
            writer.write("\n");
            writer.flush();
        } catch (IOException e) {
            log.warn("Failed to send message to {}: {}", context.getRemoteAddress(), e.getMessage());
            close();
        }
    }

    public void close() {
        running = false;
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
    }

    public SocketContext getContext() {
        return context;
    }
}
