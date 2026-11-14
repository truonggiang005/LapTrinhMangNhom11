package vn.edu.ptit.exam.server.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.common.config.Config;
import vn.edu.ptit.exam.common.dto.Message;
import vn.edu.ptit.exam.common.enums.MessageType;
import vn.edu.ptit.exam.common.util.JsonUtil;
import vn.edu.ptit.exam.server.sync.model.SyncLogEntry;
import vn.edu.ptit.exam.server.sync.model.TableHashReport;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sync receiver TCP server running on port 910N (9101-9104).
 * Listens for incoming connections from peer servers, processes SYNC_HELLO,
 * applies incoming SYNC_BATCH operations via SyncApplier, and sends SYNC_ACK.
 */
public class SyncServer {

    private static final Logger log = LoggerFactory.getLogger(SyncServer.class);

    private final String localServerId;
    private final int port;
    private final SyncApplier syncApplier;
    private final SyncPeerStateDao syncPeerStateDao;
    private final ConsistencyChecker consistencyChecker;

    private ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread acceptThread;
    private final ExecutorService clientPool = Executors.newCachedThreadPool();

    public SyncServer(String localServerId, int port, SyncApplier syncApplier,
                      SyncPeerStateDao syncPeerStateDao, ConsistencyChecker consistencyChecker) {
        this.localServerId = localServerId;
        this.port = port;
        this.syncApplier = syncApplier;
        this.syncPeerStateDao = syncPeerStateDao;
        this.consistencyChecker = consistencyChecker;
    }

    public void start() throws IOException {
        if (running.compareAndSet(false, true)) {
            serverSocket = new ServerSocket(port);
            acceptThread = new Thread(this::acceptLoop, localServerId + "-SyncServer-Acceptor");
            acceptThread.setDaemon(true);
            acceptThread.start();
            log.info("SyncServer started for {} on sync port {}", localServerId, port);
        }
    }

    public void stop() {
        running.set(false);
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {}
        clientPool.shutdownNow();
    }

    private void acceptLoop() {
        while (running.get() && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                clientSocket.setTcpNoDelay(true);
                clientPool.submit(() -> handleClient(clientSocket));
            } catch (IOException e) {
                if (running.get()) {
                    log.error("SyncServer accept error on port {}: {}", port, e.getMessage());
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        String remote = String.valueOf(socket.getRemoteSocketAddress());
        log.debug("Incoming sync connection from {}", remote);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {

            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                Message request = JsonUtil.fromJson(line, Message.class);
                Message response = processMessage(request);
                if (response != null) {
                    writer.write(JsonUtil.toJson(response) + "\n");
                    writer.flush();
                }
            }
        } catch (IOException e) {
            log.debug("Sync connection closed with {}: {}", remote, e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    private Message processMessage(Message request) {
        if (request == null || request.getType() == null) return null;

        String type = request.getType();

        if (MessageType.SYNC_HELLO.name().equalsIgnoreCase(type)) {
            return handleHello(request);
        } else if (MessageType.SYNC_BATCH.name().equalsIgnoreCase(type)) {
            return handleBatch(request);
        } else if (MessageType.SYNC_CONSISTENCY_REQUEST.name().equalsIgnoreCase(type)) {
            return handleConsistencyRequest(request);
        }

        return null;
    }

    private Message handleHello(Message request) {
        Map<String, Long> lastSeen = syncPeerStateDao.getLastSeenSeqs();
        Map<String, Object> respPayload = new HashMap<>();
        respPayload.put("serverId", localServerId);
        respPayload.put("lastSeenSeqs", lastSeen);

        Message resp = new Message();
        resp.setType(MessageType.SYNC_HELLO);
        resp.setRequestId(request.getRequestId());
        resp.setOrigin(localServerId);
        resp.setPayload(respPayload);
        return resp;
    }

    private Message handleBatch(Message request) {
        String origin = request.getOrigin();
        List<SyncLogEntry> entries = null;
        long sendTime = 0L;

        if (request.getPayload() != null) {
            JsonNode pNode = JsonUtil.readTree(JsonUtil.toJson(request.getPayload()));
            if (pNode.has("entries")) {
                entries = JsonUtil.convertValue(pNode.get("entries"), new TypeReference<List<SyncLogEntry>>() {});
            }
            if (pNode.has("sendTime")) {
                sendTime = pNode.get("sendTime").asLong();
            }
        }

        long highestAckedSeq = 0L;
        int count = 0;
        if (entries != null && !entries.isEmpty()) {
            count = entries.size();
            highestAckedSeq = syncApplier.applyBatch(origin, entries);
        }

        Map<String, Object> ackPayload = new HashMap<>();
        ackPayload.put("origin", origin);
        ackPayload.put("ackedSeq", highestAckedSeq);
        ackPayload.put("appliedCount", count);
        ackPayload.put("sendTime", sendTime);

        Message ack = new Message();
        ack.setType(MessageType.SYNC_ACK);
        ack.setRequestId(request.getRequestId());
        ack.setOrigin(localServerId);
        ack.setSeq(highestAckedSeq);
        ack.setPayload(ackPayload);
        return ack;
    }

    private Message handleConsistencyRequest(Message request) {
        Map<String, TableHashReport> reports = Collections.emptyMap();
        if (consistencyChecker != null) {
            reports = consistencyChecker.computeLocalTableHashes();
        }

        Message resp = new Message();
        resp.setType(MessageType.SYNC_CONSISTENCY_RESPONSE);
        resp.setRequestId(request.getRequestId());
        resp.setOrigin(localServerId);
        resp.setPayload(reports);
        return resp;
    }

    public int getPort() {
        return port;
    }
}
