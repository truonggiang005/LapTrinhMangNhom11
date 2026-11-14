package vn.edu.ptit.exam.server.lb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.common.dto.Message;
import vn.edu.ptit.exam.common.util.JsonUtil;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages persistent TCP connections from the Load Balancer to the 4 backend business servers.
 * Multiplexes requests over persistent sockets, matching asynchronous responses by requestId.
 */
public class BackendConnectionPool {

    private static final Logger log = LoggerFactory.getLogger(BackendConnectionPool.class);
    private static final long DEFAULT_TIMEOUT_SEC = 10L;

    public static class ServerConnection {
        private final int serverId;
        private final String host;
        private final int port;

        private Socket socket;
        private BufferedReader reader;
        private BufferedWriter writer;
        private Thread readerThread;
        private final AtomicBoolean connected = new AtomicBoolean(false);

        private final Map<String, CompletableFuture<Message>> pendingRequests = new ConcurrentHashMap<>();

        public ServerConnection(int serverId, String host, int port) {
            this.serverId = serverId;
            this.host = host;
            this.port = port;
        }

        public synchronized void ensureConnected() throws IOException {
            if (connected.get() && socket != null && !socket.isClosed() && socket.isConnected()) {
                return;
            }
            close();

            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 2000);
            socket.setTcpNoDelay(true);

            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            connected.set(true);

            readerThread = new Thread(this::runReader, "lb-to-server-" + serverId + "-reader");
            readerThread.setDaemon(true);
            readerThread.start();
            log.info("LB established connection to Server {} at {}:{}", serverId, host, port);
        }

        private void runReader() {
            try {
                String line;
                while (connected.get() && (line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    try {
                        Message resp = JsonUtil.fromJson(line, Message.class);
                        if (resp != null && resp.getRequestId() != null) {
                            CompletableFuture<Message> future = pendingRequests.remove(resp.getRequestId());
                            if (future != null) {
                                future.complete(resp);
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse backend response from Server {}: {}", serverId, line);
                    }
                }
            } catch (IOException e) {
                if (connected.get()) {
                    log.debug("Connection to Server {} closed: {}", serverId, e.getMessage());
                }
            } finally {
                handleDisconnect();
            }
        }

        public synchronized CompletableFuture<Message> send(Message message, long timeoutSec) {
            CompletableFuture<Message> future = new CompletableFuture<>();
            try {
                ensureConnected();
                String reqId = message.getRequestId();
                if (reqId == null) {
                    reqId = java.util.UUID.randomUUID().toString();
                    message.setRequestId(reqId);
                }

                future.orTimeout(timeoutSec, TimeUnit.SECONDS)
                        .whenComplete((res, ex) -> pendingRequests.remove(message.getRequestId()));

                pendingRequests.put(reqId, future);

                writer.write(JsonUtil.toJson(message) + "\n");
                writer.flush();
            } catch (Exception e) {
                pendingRequests.remove(message.getRequestId());
                future.completeExceptionally(e);
            }
            return future;
        }

        private synchronized void handleDisconnect() {
            if (connected.compareAndSet(true, false)) {
                pendingRequests.values().forEach(f -> f.completeExceptionally(new IOException("Server " + serverId + " disconnected")));
                pendingRequests.clear();
                try {
                    if (socket != null && !socket.isClosed()) socket.close();
                } catch (IOException ignored) {}
            }
        }

        public synchronized void close() {
            connected.set(false);
            if (readerThread != null) readerThread.interrupt();
            handleDisconnect();
        }

        public boolean isConnected() {
            return connected.get() && socket != null && !socket.isClosed();
        }
    }

    private final Map<Integer, ServerConnection> connections = new ConcurrentHashMap<>();

    public void registerServer(int serverId, String host, int port) {
        connections.put(serverId, new ServerConnection(serverId, host, port));
    }

    public CompletableFuture<Message> forward(int serverId, Message message) {
        return forward(serverId, message, DEFAULT_TIMEOUT_SEC);
    }

    public CompletableFuture<Message> forward(int serverId, Message message, long timeoutSec) {
        ServerConnection conn = connections.get(serverId);
        if (conn == null) {
            CompletableFuture<Message> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IOException("Unknown backend server ID: " + serverId));
            return failed;
        }
        return conn.send(message, timeoutSec);
    }

    public void close() {
        connections.values().forEach(ServerConnection::close);
        connections.clear();
    }
}
