package vn.edu.ptit.exam.server.lb;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.edu.ptit.exam.common.dto.Message;
import vn.edu.ptit.exam.common.enums.MessageType;
import vn.edu.ptit.exam.common.util.JsonUtil;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class LoadBalancerIntegrationTest {

    private ServerSocket dummyBackendSocket;
    private int backendPort;
    private Thread backendThread;
    private final AtomicBoolean backendRunning = new AtomicBoolean(false);

    private LoadBalancer loadBalancer;
    private int lbPort;

    @BeforeEach
    void setUp() throws IOException {
        // 1. Start a simple dummy backend server
        dummyBackendSocket = new ServerSocket(0);
        backendPort = dummyBackendSocket.getLocalPort();
        backendRunning.set(true);

        backendThread = new Thread(this::runDummyBackend, "dummy-backend-worker");
        backendThread.setDaemon(true);
        backendThread.start();

        // 2. Start Load Balancer on ephemeral port
        try (ServerSocket temp = new ServerSocket(0)) {
            lbPort = temp.getLocalPort();
        }

        loadBalancer = new LoadBalancer(lbPort);
        loadBalancer.setMinServersUp(1); // Accept with 1 server for unit test

        // Reconfigure backend server 1 to point to dummy backend
        BackendServerInfo s1 = loadBalancer.getBackendServer(1);
        if (s1 != null) {
            s1.forceStatus(ServerStatus.UP);
        }

        loadBalancer.start();
    }

    @AfterEach
    void tearDown() {
        if (loadBalancer != null) {
            loadBalancer.stop();
        }
        backendRunning.set(false);
        try {
            if (dummyBackendSocket != null) dummyBackendSocket.close();
        } catch (IOException ignored) {}
    }

    private void runDummyBackend() {
        while (backendRunning.get()) {
            try {
                Socket sock = dummyBackendSocket.accept();
                new Thread(() -> {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
                         BufferedWriter w = new BufferedWriter(new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            Message msg = JsonUtil.fromJson(line, Message.class);
                            if (msg != null) {
                                Message resp = Message.ok(msg.getType(), msg.getRequestId(), Map.of("backend", "OK"));
                                resp.setServerTime(System.currentTimeMillis());
                                w.write(JsonUtil.toJson(resp) + "\n");
                                w.flush();
                            }
                        }
                    } catch (IOException ignored) {}
                }).start();
            } catch (IOException ignored) {}
        }
    }

    @Test
    void testDirectPingAndAdminStatusOverSocket() throws Exception {
        try (Socket clientSocket = new Socket("127.0.0.1", lbPort);
             BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8))) {

            // 1. Test direct PING to LB
            Message pingReq = Message.request(MessageType.PING, "REQ-PING-1");
            writer.write(JsonUtil.toJson(pingReq) + "\n");
            writer.flush();

            String line = reader.readLine();
            assertNotNull(line);
            Message pongResp = JsonUtil.fromJson(line, Message.class);
            assertEquals("PONG", pongResp.getType());
            assertEquals("REQ-PING-1", pongResp.getRequestId());
            assertTrue(pongResp.isOk());

            // 2. Test ADMIN_STATUS query
            Message statusReq = Message.request(MessageType.ADMIN_STATUS, "REQ-STATUS-1");
            writer.write(JsonUtil.toJson(statusReq) + "\n");
            writer.flush();

            String statusLine = reader.readLine();
            assertNotNull(statusLine);
            Message statusResp = JsonUtil.fromJson(statusLine, Message.class);
            assertEquals(MessageType.ADMIN_STATUS.name(), statusResp.getType());
            assertTrue(statusResp.isOk());
            assertNotNull(statusResp.getPayload());

            // 3. Test ADMIN_SET_ALGO to switch algorithm
            Message setAlgoReq = Message.request(MessageType.ADMIN_SET_ALGO, "REQ-ALGO-1");
            setAlgoReq.setPayload(Map.of("algorithm", "ROUND_ROBIN"));
            writer.write(JsonUtil.toJson(setAlgoReq) + "\n");
            writer.flush();

            String algoLine = reader.readLine();
            assertNotNull(algoLine);
            Message algoResp = JsonUtil.fromJson(algoLine, Message.class);
            assertTrue(algoResp.isOk());
            assertEquals(AlgorithmType.ROUND_ROBIN, loadBalancer.getServerSelector().getAlgorithm());
        }
    }
}
