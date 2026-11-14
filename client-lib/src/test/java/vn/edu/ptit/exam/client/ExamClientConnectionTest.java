package vn.edu.ptit.exam.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.edu.ptit.exam.client.event.ExamClientAdapter;
import vn.edu.ptit.exam.client.mock.MockExamServer;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class ExamClientConnectionTest {

    private MockExamServer mockServer;
    private ExamClient client;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockExamServer();
        mockServer.setSimulatedServerTimeOffset(3000L); // 3 seconds forward
        mockServer.start();
        client = new ExamClient();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.disconnect();
        }
        if (mockServer != null) {
            mockServer.stop();
        }
    }

    @Test
    void testConnectAndClockSync() throws Exception {
        CountDownLatch connectedLatch = new CountDownLatch(1);
        client.addListener(new ExamClientAdapter() {
            @Override
            public void onConnected(String host, int port) {
                connectedLatch.countDown();
            }
        });

        client.connect("127.0.0.1", mockServer.getPort()).get(5, TimeUnit.SECONDS);

        assertTrue(connectedLatch.await(3, TimeUnit.SECONDS));
        assertTrue(client.isConnected());

        // Invariant 6: Offset is calculated from 3 PINGs with min RTT
        // Simulated offset is 3000ms, so client clockOffset should be close to 3000ms (+/- 500ms)
        long offset = client.getClockOffset();
        assertTrue(Math.abs(offset - 3000L) < 500, "Clock offset should be close to 3000ms, but was " + offset);

        // At least 3 PING requests should be recorded on server
        long pingCount = mockServer.getReceivedRequests().stream()
                .filter(m -> "PING".equals(m.getType()))
                .count();
        assertTrue(pingCount >= 3, "Expected at least 3 initial PINGs, but got " + pingCount);
    }

    @Test
    void testDisconnectEvent() throws Exception {
        CountDownLatch disconnectLatch = new CountDownLatch(1);
        AtomicBoolean disconnected = new AtomicBoolean(false);

        client.addListener(new ExamClientAdapter() {
            @Override
            public void onDisconnected(String reason) {
                disconnected.set(true);
                disconnectLatch.countDown();
            }
        });

        client.connect("127.0.0.1", mockServer.getPort()).get(5, TimeUnit.SECONDS);
        assertTrue(client.isConnected());

        // Trigger manual disconnect
        client.disconnect();

        assertTrue(disconnectLatch.await(3, TimeUnit.SECONDS));
        assertTrue(disconnected.get());
        assertFalse(client.isConnected());
    }
}
