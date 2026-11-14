package vn.edu.ptit.exam.client.net;

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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Low-level TCP socket connection with dedicated writer and reader threads.
 * Matches asynchronous request/response via requestId using CompletableFuture with a 10s timeout.
 */
public class SocketConnection {

    private static final Logger log = LoggerFactory.getLogger(SocketConnection.class);
    private static final long DEFAULT_TIMEOUT_SECONDS = 10L;

    private final String host;
    private final int port;
    private final Consumer<Message> pushMessageConsumer;
    private final Consumer<String> disconnectHandler;

    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;

    private final BlockingQueue<Message> sendQueue = new LinkedBlockingQueue<>();
    private final Map<String, CompletableFuture<Message>> pendingRequests = new ConcurrentHashMap<>();

    private Thread writerThread;
    private Thread readerThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public SocketConnection(String host, int port,
                            Consumer<Message> pushMessageConsumer,
                            Consumer<String> disconnectHandler) {
        this.host = host;
        this.port = port;
        this.pushMessageConsumer = pushMessageConsumer;
        this.disconnectHandler = disconnectHandler;
    }

    /**
     * Connect to target host:port and launch reader/writer threads.
     */
    public synchronized void connect() throws IOException {
        if (running.get()) {
            return;
        }

        socket = new Socket();
        // Set connect timeout 5 seconds
        socket.connect(new InetSocketAddress(host, port), 5000);
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);

        reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

        running.set(true);

        // Dedicated Writer Thread
        writerThread = new Thread(this::runWriterLoop, "exam-socket-writer");
        writerThread.setDaemon(true);
        writerThread.start();

        // Dedicated Reader Thread
        readerThread = new Thread(this::runReaderLoop, "exam-socket-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        log.info("Connected to {}:{}", host, port);
    }

    /**
     * Send a request message expecting a matching response by requestId.
     */
    public CompletableFuture<Message> sendRequest(Message message) {
        return sendRequest(message, DEFAULT_TIMEOUT_SECONDS);
    }

    public CompletableFuture<Message> sendRequest(Message message, long timeoutSeconds) {
        if (!isConnected()) {
            CompletableFuture<Message> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IOException("Socket is not connected"));
            return failed;
        }

        String reqId = message.getRequestId();
        if (reqId == null || reqId.isBlank()) {
            reqId = java.util.UUID.randomUUID().toString();
            message.setRequestId(reqId);
        }

        CompletableFuture<Message> future = new CompletableFuture<>();
        // Set timeout
        future.orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .whenComplete((res, ex) -> pendingRequests.remove(message.getRequestId()));

        pendingRequests.put(reqId, future);
        sendQueue.offer(message);
        return future;
    }

    /**
     * Send message fire-and-forget without waiting for correlated response.
     */
    public void sendDirect(Message message) {
        if (isConnected()) {
            sendQueue.offer(message);
        }
    }

    public boolean isConnected() {
        return running.get() && socket != null && !socket.isClosed() && socket.isConnected();
    }

    private void runWriterLoop() {
        log.debug("Socket writer thread started");
        try {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                Message msg = sendQueue.poll(200, TimeUnit.MILLISECONDS);
                if (msg != null) {
                    String json = JsonUtil.toJson(msg);
                    writer.write(json);
                    writer.write("\n");
                    writer.flush();
                    log.debug("Sent -> type={}, reqId={}", msg.getType(), msg.getRequestId());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Socket writer error: {}", e.getMessage());
            handleSocketFailure("Writer failure: " + e.getMessage());
        }
        log.debug("Socket writer thread terminated");
    }

    private void runReaderLoop() {
        log.debug("Socket reader thread started");
        try {
            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    Message msg = JsonUtil.fromJson(line, Message.class);
                    if (msg == null) {
                        continue;
                    }
                    log.debug("Received <- type={}, reqId={}, status={}", msg.getType(), msg.getRequestId(), msg.getStatus());

                    String reqId = msg.getRequestId();
                    CompletableFuture<Message> future = (reqId != null) ? pendingRequests.remove(reqId) : null;
                    if (future != null) {
                        future.complete(msg);
                    } else if (pushMessageConsumer != null) {
                        pushMessageConsumer.accept(msg);
                    }
                } catch (Exception parseEx) {
                    log.warn("Failed to parse incoming message line: {}", line, parseEx);
                }
            }
            log.info("Socket reader reached EOF (server closed connection)");
            handleSocketFailure("Server closed connection");
        } catch (Exception e) {
            if (running.get()) {
                log.warn("Socket reader error: {}", e.getMessage());
                handleSocketFailure("Reader failure: " + e.getMessage());
            }
        }
        log.debug("Socket reader thread terminated");
    }

    private synchronized void handleSocketFailure(String reason) {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        // Cancel all pending requests
        IOException disconnectEx = new IOException("Connection lost: " + reason);
        pendingRequests.values().forEach(f -> f.completeExceptionally(disconnectEx));
        pendingRequests.clear();
        sendQueue.clear();

        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
        }

        if (disconnectHandler != null) {
            disconnectHandler.accept(reason);
        }
    }

    public synchronized void close() {
        running.set(false);
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
        }

        if (writerThread != null) {
            writerThread.interrupt();
        }
        if (readerThread != null) {
            readerThread.interrupt();
        }

        pendingRequests.values().forEach(f -> f.completeExceptionally(new IOException("Socket connection closed")));
        pendingRequests.clear();
        sendQueue.clear();
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }
}
