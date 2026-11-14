package vn.edu.ptit.exam.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.edu.ptit.exam.client.event.ExamClientAdapter;
import vn.edu.ptit.exam.client.mock.MockExamServer;
import vn.edu.ptit.exam.client.storage.LocalAttemptData;
import vn.edu.ptit.exam.client.storage.LocalAttemptStorage;
import vn.edu.ptit.exam.common.dto.AnswerItemDto;
import vn.edu.ptit.exam.common.enums.MessageType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class ExamClientOfflineAndRecoveryTest {

    private MockExamServer mockServer;
    private ExamClient client;
    private Path tempStorageDir;
    private LocalAttemptStorage storage;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockExamServer();
        mockServer.start();

        tempStorageDir = Files.createTempDirectory("exam-recovery-test-");
        storage = new LocalAttemptStorage(tempStorageDir);
        client = new ExamClient(storage);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.disconnect();
        }
        if (mockServer != null) {
            mockServer.stop();
        }
        if (storage != null) {
            storage.shutdown();
        }
        if (tempStorageDir != null) {
            try {
                Files.walk(tempStorageDir)
                        .sorted((a, b) -> b.compareTo(a))
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {}
                        });
            } catch (IOException ignored) {}
        }
    }

    @Test
    void testLocalAttemptStoragePersistence() throws Exception {
        LocalAttemptData data = new LocalAttemptData(
                "202", "1", "Kiểm tra Mạng", "Lập trình mạng",
                60, System.currentTimeMillis(), System.currentTimeMillis() + 3600_000L,
                "saved-token"
        );
        data.getAnswers().put("q1", new AnswerItemDto("q1", "opt2", false));
        data.getAnswers().put("q2", new AnswerItemDto("q2", "opt4", true));

        // Save snapshot asynchronously
        storage.saveAttemptAsync(data).get(3, TimeUnit.SECONDS);

        // Verify loaded attempt matches
        Optional<LocalAttemptData> loaded = storage.loadAttempt("202");
        assertTrue(loaded.isPresent());
        assertEquals("202", loaded.get().getAttemptId());
        assertEquals("Kiểm tra Mạng", loaded.get().getExamTitle());
        assertEquals(2, loaded.get().getAnswers().size());
        assertEquals("opt2", loaded.get().getAnswers().get("q1").getSelectedOptionId());
        assertTrue(loaded.get().getAnswers().get("q2").isMarked());

        // Verify findLatestIncompleteAttempt
        Optional<LocalAttemptData> incomplete = storage.findLatestIncompleteAttempt();
        assertTrue(incomplete.isPresent());
        assertEquals("202", incomplete.get().getAttemptId());

        // Delete attempt
        storage.deleteAttempt("202");
        assertFalse(storage.loadAttempt("202").isPresent());
    }

    @Test
    void testDisconnectAndAutomaticReconnectWithSessionResume() throws Exception {
        CountDownLatch reconnectingLatch = new CountDownLatch(1);
        CountDownLatch reconnectedLatch = new CountDownLatch(1);
        AtomicBoolean sessionResumed = new AtomicBoolean(false);

        mockServer.setCustomHandler(MessageType.RESUME_SESSION.name(), req -> {
            sessionResumed.set(true);
            return mockServer.generateDefaultResponse(req);
        });

        client.addListener(new ExamClientAdapter() {
            @Override
            public void onReconnecting(int attemptCount, int delaySeconds) {
                reconnectingLatch.countDown();
            }

            @Override
            public void onConnected(String host, int port) {
                // Ignore initial connect, count second connect
                if (reconnectingLatch.getCount() == 0) {
                    reconnectedLatch.countDown();
                }
            }
        });

        // 1. Initial connect and login
        client.connect("127.0.0.1", mockServer.getPort()).get(5, TimeUnit.SECONDS);
        client.login("student01", "password").get(5, TimeUnit.SECONDS);

        // 2. Abruptly disconnect client socket from server side
        mockServer.disconnectAllClients();

        // 3. Verify reconnecting event is fired (1s backoff)
        assertTrue(reconnectingLatch.await(4, TimeUnit.SECONDS), "onReconnecting should be called");

        // 4. Verify reconnected event is fired and RESUME_SESSION was sent
        assertTrue(reconnectedLatch.await(5, TimeUnit.SECONDS), "Client should reconnect automatically");
        assertTrue(client.isConnected());

        // Give a moment for resume session to process
        Thread.sleep(500);

        long resumeCount = mockServer.getReceivedRequests().stream()
                .filter(m -> MessageType.RESUME_SESSION.name().equals(m.getType()))
                .count();
        assertTrue(resumeCount >= 1, "RESUME_SESSION should be sent after reconnecting");
    }
}
