package vn.edu.ptit.exam.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.edu.ptit.exam.client.event.ExamClientAdapter;
import vn.edu.ptit.exam.client.mock.MockExamServer;
import vn.edu.ptit.exam.client.storage.LocalAttemptStorage;
import vn.edu.ptit.exam.common.dto.ExamListResponse;
import vn.edu.ptit.exam.common.dto.Message;
import vn.edu.ptit.exam.common.dto.SaveAnswersResponse;
import vn.edu.ptit.exam.common.dto.StartExamResponse;
import vn.edu.ptit.exam.common.dto.SubmitResultDto;
import vn.edu.ptit.exam.common.enums.MessageType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class ExamClientExamFlowTest {

    private MockExamServer mockServer;
    private ExamClient client;
    private Path tempStorageDir;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockExamServer();
        mockServer.start();

        tempStorageDir = Files.createTempDirectory("exam-test-cache-");
        LocalAttemptStorage storage = new LocalAttemptStorage(tempStorageDir);
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
    void testListExams30sCache() throws Exception {
        client.connect("127.0.0.1", mockServer.getPort()).get(5, TimeUnit.SECONDS);

        // 1. First call -> sends network request
        ExamListResponse resp1 = client.listExams(null, null, 1, 10, false).get(5, TimeUnit.SECONDS);
        assertNotNull(resp1);
        assertEquals(1, resp1.getTotalRows());

        long countAfterFirst = mockServer.getReceivedRequests().stream()
                .filter(m -> MessageType.LIST_EXAMS.name().equals(m.getType()))
                .count();
        assertEquals(1, countAfterFirst);

        // 2. Second call with same parameters within 30s -> served from cache
        ExamListResponse resp2 = client.listExams(null, null, 1, 10, false).get(5, TimeUnit.SECONDS);
        assertNotNull(resp2);

        long countAfterSecond = mockServer.getReceivedRequests().stream()
                .filter(m -> MessageType.LIST_EXAMS.name().equals(m.getType()))
                .count();
        assertEquals(1, countAfterSecond, "Second call within 30s should be served from memory cache");

        // 3. Force refresh -> bypasses cache and sends request
        ExamListResponse resp3 = client.listExams(null, null, 1, 10, true).get(5, TimeUnit.SECONDS);
        assertNotNull(resp3);

        long countAfterForce = mockServer.getReceivedRequests().stream()
                .filter(m -> MessageType.LIST_EXAMS.name().equals(m.getType()))
                .count();
        assertEquals(2, countAfterForce, "forceRefresh should bypass memory cache");
    }

    @Test
    void testStartExamAndAnswerWorkflow() throws Exception {
        CountDownLatch clockTickLatch = new CountDownLatch(1);
        CountDownLatch saveStatusLatch = new CountDownLatch(1);
        AtomicBoolean savedConfirmed = new AtomicBoolean(false);

        client.addListener(new ExamClientAdapter() {
            @Override
            public void onClockTick(long remainingSeconds) {
                clockTickLatch.countDown();
            }

            @Override
            public void onSaveStatusChanged(String statusText, boolean isError) {
                if (statusText.contains("Đã lưu")) {
                    savedConfirmed.set(true);
                    saveStatusLatch.countDown();
                }
            }
        });

        client.connect("127.0.0.1", mockServer.getPort()).get(5, TimeUnit.SECONDS);

        // 1. Start exam
        StartExamResponse startResp = client.startExam("1").get(5, TimeUnit.SECONDS);
        assertNotNull(startResp);
        assertEquals("101", startResp.getAttemptId());

        // Background clock thread should tick
        assertTrue(clockTickLatch.await(3, TimeUnit.SECONDS), "Clock countdown thread should tick");

        // 2. Set answer
        client.setAnswer("q1", "o1", false);
        assertEquals(1, client.getAnswersMap().size());
        assertTrue(client.getDirtyQuestionIds().contains("q1"));

        // 3. Question changed -> triggers immediate auto-save
        client.onQuestionChanged("q1", "q2");

        assertTrue(saveStatusLatch.await(3, TimeUnit.SECONDS), "Save status should confirm 'Đã lưu'");
        assertTrue(savedConfirmed.get());
        assertFalse(client.getDirtyQuestionIds().contains("q1"), "Dirty flag should be cleared after save");

        // 4. Submit exam
        SubmitResultDto submitResult = client.submit("101").get(5, TimeUnit.SECONDS);
        assertNotNull(submitResult);
        assertEquals("SUBMITTED", submitResult.getStatus());
        assertEquals(9.5, submitResult.getScore());

        // Invariant 4 Check: verify fixed requestId "SUBMIT-<attemptId>"
        Message submitReq = mockServer.getReceivedRequests().stream()
                .filter(m -> MessageType.SUBMIT.name().equals(m.getType()))
                .findFirst()
                .orElse(null);

        assertNotNull(submitReq);
        assertEquals("SUBMIT-101", submitReq.getRequestId(), "Invariant 4: SUBMIT requestId must be 'SUBMIT-<attemptId>'");

        // Idempotent test: calling submit again should return cached result without sending duplicate SUBMIT
        long submitCountBefore = mockServer.getReceivedRequests().stream()
                .filter(m -> MessageType.SUBMIT.name().equals(m.getType()))
                .count();

        SubmitResultDto repeatSubmitResult = client.submit("101").get(5, TimeUnit.SECONDS);
        assertEquals(submitResult.getScore(), repeatSubmitResult.getScore());

        long submitCountAfter = mockServer.getReceivedRequests().stream()
                .filter(m -> MessageType.SUBMIT.name().equals(m.getType()))
                .count();
        assertEquals(submitCountBefore, submitCountAfter, "Subsequent submit calls must be idempotent and not re-sent");
    }
}
