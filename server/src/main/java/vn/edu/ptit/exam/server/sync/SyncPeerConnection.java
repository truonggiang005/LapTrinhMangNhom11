package vn.edu.ptit.exam.server.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.common.config.Config;
import vn.edu.ptit.exam.common.dto.Message;
import vn.edu.ptit.exam.common.enums.MessageType;
import vn.edu.ptit.exam.common.util.JsonUtil;
import vn.edu.ptit.exam.server.sync.model.SyncLogEntry;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages an outgoing persistent TCP connection to a single peer server's sync port.
 * Handles heartbeats, timeout detection (3s), reconnects with backoff (1, 2, 4, 8, 16, 30s),
 * SYNC_HELLO handshake, sending SYNC_BATCH, and receiving SYNC_ACK.
 */
public class SyncPeerConnection {

    private static final Logger log = LoggerFactory.getLogger(SyncPeerConnection.class);

    private final String localServerId;
    private final String peerId;
    private final int peerIndex;
    private final String host;
    private final int port;
    private final SyncPeerStateDao syncPeerStateDao;
    private final SyncAckListener ackListener;

    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;
    private final Object writeLock = new Object();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);

    private Thread workerThread;
    private final AtomicLong lastAckReceivedTime = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong lastSentSeq = new AtomicLong(0);
    private final AtomicLong currentLatencyMs = new AtomicLong(0);

    private static final int[] BACKOFF_SECONDS = {1, 2, 4, 8, 16, 30};
    private int backoffIndex = 0;

    public interface SyncAckListener {
        void onAckReceived(String peerId, String origin, long ackedSeq);
    }

    public SyncPeerConnection(String localServerId, int peerIndex, SyncPeerStateDao syncPeerStateDao,
                              SyncAckListener ackListener) {
        this.localServerId = localServerId;
        this.peerIndex = peerIndex;
        Config config = Config.getInstance();
        this.peerId = config.getServerId(peerIndex);
        this.host = config.getServerHost(peerIndex);
        this.port = config.getServerSyncPort(peerIndex);
        this.syncPeerStateDao = syncPeerStateDao;
        this.ackListener = ackListener;
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            workerThread = new Thread(this::connectionLoop, localServerId + "-SyncPeer-" + peerId);
            workerThread.setDaemon(true);
            workerThread.start();
        }
    }

    public void stop() {
        running.set(false);
        closeSocket();
        if (workerThread != null) {
            workerThread.interrupt();
        }
    }

    private void connectionLoop() {
        while (running.get()) {
            try {
                if (!connected.get()) {
                    attemptConnect();
                }

                if (connected.get()) {
                    readLoop();
                }
            } catch (Exception e) {
                if (running.get()) {
                    log.debug("Sync connection loop exception for peer {}: {}", peerId, e.getMessage());
                }
            }

            if (running.get()) {
                handleDisconnect();
                sleepBackoff();
            }
        }
    }

    private void attemptConnect() {
        try {
            log.info("Connecting to peer {} sync port at {}:{}...", peerId, host, port);
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 3000);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(5000); // 5s read timeout to detect dead connection

            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            connected.set(true);
            backoffIndex = 0; // Reset backoff on successful connect
            lastAckReceivedTime.set(System.currentTimeMillis());
            syncPeerStateDao.setPeerStatus(peerId, "ACTIVE");
            log.info("Connected to peer {} sync port at {}:{}", peerId, host, port);

            // Send SYNC_HELLO handshake
            sendHello();
        } catch (Exception e) {
            connected.set(false);
            closeSocket();
            syncPeerStateDao.setPeerStatus(peerId, "DOWN");
            log.warn("Cannot connect to peer {} at {}:{}: {}", peerId, host, port, e.getMessage());
        }
    }

    private void sendHello() {
        try {
            Map<String, Long> lastSeen = syncPeerStateDao.getLastSeenSeqs();
            Map<String, Object> payload = new HashMap<>();
            payload.put("serverId", localServerId);
            payload.put("lastSeenSeqs", lastSeen);

            Message helloMsg = new Message();
            helloMsg.setType(MessageType.SYNC_HELLO);
            helloMsg.setRequestId(UUID.randomUUID().toString());
            helloMsg.setOrigin(localServerId);
            helloMsg.setPayload(payload);

            sendMessage(helloMsg);
            log.info("Sent SYNC_HELLO to peer {}", peerId);
        } catch (Exception e) {
            log.error("Failed to send SYNC_HELLO to {}: {}", peerId, e.getMessage());
        }
    }

    private void readLoop() {
        while (running.get() && connected.get()) {
            try {
                String line = reader.readLine();
                if (line == null) {
                    log.warn("Peer {} closed sync socket", peerId);
                    break;
                }

                line = line.trim();
                if (line.isEmpty()) continue;

                Message msg = JsonUtil.fromJson(line, Message.class);
                handleIncomingMessage(msg);
            } catch (SocketTimeoutException te) {
                // Check 3s ack timeout condition
                long elapsed = System.currentTimeMillis() - lastAckReceivedTime.get();
                if (elapsed > 3000 && lastSentSeq.get() > 0) {
                    log.warn("Peer {} did not ACK within 3s (elapsed {}ms), marking DOWN", peerId, elapsed);
                    syncPeerStateDao.setPeerStatus(peerId, "DOWN");
                }
            } catch (IOException e) {
                if (running.get() && connected.get()) {
                    log.warn("Read error from peer {}: {}", peerId, e.getMessage());
                }
                break;
            }
        }
    }

    private void handleIncomingMessage(Message msg) {
        if (msg == null || msg.getType() == null) return;

        if (MessageType.SYNC_ACK.name().equalsIgnoreCase(msg.getType())) {
            long now = System.currentTimeMillis();
            lastAckReceivedTime.set(now);
            syncPeerStateDao.setPeerStatus(peerId, "ACTIVE");

            String origin = msg.getOrigin() != null ? msg.getOrigin() : localServerId;
            long ackedSeq = msg.getSeq() != null ? msg.getSeq() : 0L;

            if (msg.getPayload() != null) {
                Map<?, ?> pMap = JsonUtil.convertValue(msg.getPayload(), Map.class);
                if (pMap.containsKey("origin")) origin = String.valueOf(pMap.get("origin"));
                if (pMap.containsKey("ackedSeq")) ackedSeq = ((Number) pMap.get("ackedSeq")).longValue();
                if (pMap.containsKey("sendTime")) {
                    long sendTime = ((Number) pMap.get("sendTime")).longValue();
                    currentLatencyMs.set(Math.max(0, now - sendTime));
                }
            }

            syncPeerStateDao.updateLastAckedSeq(peerId, ackedSeq);
            log.debug("Received SYNC_ACK from peer {}: origin={}, ackedSeq={}", peerId, origin, ackedSeq);

            if (ackListener != null) {
                ackListener.onAckReceived(peerId, origin, ackedSeq);
            }
        } else if (MessageType.SYNC_HELLO.name().equalsIgnoreCase(msg.getType())) {
            log.info("Received SYNC_HELLO response from peer {}", peerId);
            handlePeerHello(msg);
        }
    }

    private void handlePeerHello(Message msg) {
        if (msg.getPayload() == null) return;
        try {
            Map<?, ?> pMap = JsonUtil.convertValue(msg.getPayload(), Map.class);
            Map<?, ?> peerLastSeen = (Map<?, ?>) pMap.get("lastSeenSeqs");
            if (peerLastSeen != null) {
                Object seqObj = peerLastSeen.get(localServerId);
                long peerSeenOurSeq = seqObj instanceof Number ? ((Number) seqObj).longValue() : 0L;
                log.info("Peer {} last saw our seq: {}", peerId, peerSeenOurSeq);

                // Update peer acked seq to what it already saw
                syncPeerStateDao.updateLastAckedSeq(peerId, peerSeenOurSeq);

                // Check if catch-up needed
                long ourCurrentSeq = syncPeerStateDao.countPendingLogs(localServerId);
                long diff = ourCurrentSeq - peerSeenOurSeq;
                if (diff > 50000 || syncPeerStateDao.wereLogsPurged(localServerId, peerSeenOurSeq)) {
                    log.warn("Delta for peer {} is large (diff={}, purged), table sync needed", peerId, diff);
                }
            }
        } catch (Exception e) {
            log.error("Error processing peer SYNC_HELLO: {}", e.getMessage(), e);
        }
    }

    public boolean sendBatch(List<SyncLogEntry> entries) {
        if (!connected.get() || entries == null || entries.isEmpty()) return false;

        try {
            long maxSeq = entries.get(entries.size() - 1).getSeq();
            Map<String, Object> payload = new HashMap<>();
            payload.put("peerId", peerId);
            payload.put("entries", entries);
            payload.put("sendTime", System.currentTimeMillis());

            Message msg = new Message();
            msg.setType(MessageType.SYNC_BATCH);
            msg.setRequestId(UUID.randomUUID().toString());
            msg.setOrigin(localServerId);
            msg.setSeq(maxSeq);
            msg.setPayload(payload);

            lastSentSeq.set(maxSeq);
            sendMessage(msg);
            log.debug("Sent SYNC_BATCH to peer {} with {} entries, maxSeq={}", peerId, entries.size(), maxSeq);
            return true;
        } catch (Exception e) {
            log.error("Failed to send SYNC_BATCH to peer {}: {}", peerId, e.getMessage());
            handleDisconnect();
            return false;
        }
    }

    public void sendMessage(Message msg) throws IOException {
        String json = JsonUtil.toJson(msg) + "\n";
        synchronized (writeLock) {
            if (writer != null) {
                writer.write(json);
                writer.flush();
            } else {
                throw new IOException("Writer is closed");
            }
        }
    }

    private void handleDisconnect() {
        if (connected.compareAndSet(true, false)) {
            syncPeerStateDao.setPeerStatus(peerId, "DOWN");
            log.warn("Disconnected from peer {}", peerId);
            closeSocket();
        }
    }

    private void closeSocket() {
        try {
            if (writer != null) writer.close();
        } catch (Exception ignored) {}
        try {
            if (reader != null) reader.close();
        } catch (Exception ignored) {}
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (Exception ignored) {}
        writer = null;
        reader = null;
        socket = null;
    }

    private void sleepBackoff() {
        int seconds = BACKOFF_SECONDS[Math.min(backoffIndex, BACKOFF_SECONDS.length - 1)];
        if (backoffIndex < BACKOFF_SECONDS.length - 1) {
            backoffIndex++;
        }
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isConnected() {
        return connected.get();
    }

    public String getPeerId() {
        return peerId;
    }

    public long getLatencyMs() {
        return currentLatencyMs.get();
    }
}
