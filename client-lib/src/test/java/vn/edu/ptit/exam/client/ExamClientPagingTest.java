package vn.edu.ptit.exam.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.edu.ptit.exam.client.mock.MockExamServer;
import vn.edu.ptit.exam.common.dto.Message;
import vn.edu.ptit.exam.common.dto.ScoreItemDto;
import vn.edu.ptit.exam.common.enums.MessageType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class ExamClientPagingTest {

    private MockExamServer mockServer;
    private ExamClient client;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockExamServer();
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
    void testFetchAllScoresPaginationWithProgressCallback() throws Exception {
        int totalRows = 350;

        mockServer.setCustomHandler(MessageType.GET_SCORES.name(), req -> {
            Map<?, ?> payload = (Map<?, ?>) req.getPayload();
            int page = (int) payload.get("page");
            int pageSize = (int) payload.get("pageSize");

            int start = (page - 1) * pageSize;
            int end = Math.min(start + pageSize, totalRows);

            List<Map<String, Object>> pageScores = new ArrayList<>();
            for (int i = start; i < end; i++) {
                pageScores.add(Map.of(
                        "studentId", String.valueOf(i + 1),
                        "studentCode", "B21DCCN" + String.format("%03d", i + 1),
                        "fullName", "Sinh Vien " + (i + 1),
                        "score", 8.0,
                        "correctCount", 16,
                        "totalQuestions", 20,
                        "status", "SUBMITTED"
                ));
            }

            Map<String, Object> respPayload = Map.of(
                    "scores", pageScores,
                    "totalRows", totalRows,
                    "page", page,
                    "pageSize", pageSize,
                    "isFinalized", false
            );
            return Message.ok(MessageType.GET_SCORES, req.getRequestId(), respPayload);
        });

        client.connect("127.0.0.1", mockServer.getPort()).get(5, TimeUnit.SECONDS);

        List<Double> progressUpdates = new CopyOnWriteArrayList<>();
        List<ScoreItemDto> allScores = client.fetchAllScores("1", progressUpdates::add).get(10, TimeUnit.SECONDS);

        assertNotNull(allScores);
        assertEquals(350, allScores.size(), "Should have retrieved all 350 score records across pages");
        assertFalse(progressUpdates.isEmpty(), "Progress callback should have been invoked");
        assertEquals(1.0, progressUpdates.get(progressUpdates.size() - 1), 0.001, "Final progress update should be 1.0 (100%)");
    }
}
