package vn.edu.ptit.exam.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.edu.ptit.exam.client.mock.MockExamServer;
import vn.edu.ptit.exam.common.dto.*;
import vn.edu.ptit.exam.common.enums.MessageType;
import vn.edu.ptit.exam.common.enums.UserRole;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class ExamClientLecturerAdminTest {

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
    @DisplayName("Lecturer Question CRUD via ExamClient")
    void testQuestionOperations() throws Exception {
        // Connect & login as Lecturer
        client.connect("localhost", mockServer.getPort()).get(5, TimeUnit.SECONDS);
        client.login("gv_lan", "pass").get(5, TimeUnit.SECONDS);

        // Mock QUESTION_CREATE
        mockServer.setCustomHandler(MessageType.QUESTION_CREATE.name(), req -> {
            QuestionDto q = new QuestionDto();
            q.setId("q-client-1");
            q.setContent("HTTP là gì?");
            q.setVersion(1);
            return Message.ok(MessageType.QUESTION_CREATE, req.getRequestId(), q);
        });

        QuestionDto createReq = new QuestionDto();
        createReq.setContent("HTTP là gì?");
        createReq.setSubject("LTM");
        QuestionDto created = client.createQuestion(createReq).get(5, TimeUnit.SECONDS);
        assertEquals("q-client-1", created.getId());
        assertEquals(1, created.getVersion());

        // Mock QUESTION_UPDATE
        mockServer.setCustomHandler(MessageType.QUESTION_UPDATE.name(), req -> {
            QuestionDto q = new QuestionDto();
            q.setId("q-client-1");
            q.setContent("HTTP 1.1 là gì?");
            q.setVersion(2);
            return Message.ok(MessageType.QUESTION_UPDATE, req.getRequestId(), q);
        });

        createReq.setId("q-client-1");
        createReq.setContent("HTTP 1.1 là gì?");
        QuestionDto updated = client.updateQuestion(createReq, 1).get(5, TimeUnit.SECONDS);
        assertEquals(2, updated.getVersion());

        // Mock QUESTION_DELETE
        mockServer.setCustomHandler(MessageType.QUESTION_DELETE.name(), req ->
                Message.ok(MessageType.QUESTION_DELETE, req.getRequestId(), Map.of("success", true)));

        assertDoesNotThrow(() -> client.deleteQuestion("q-client-1", 2).get(5, TimeUnit.SECONDS));

        // Mock QUESTION_LIST
        mockServer.setCustomHandler(MessageType.QUESTION_LIST.name(), req -> {
            QuestionListResponse resp = new QuestionListResponse(
                    Collections.singletonList(created), 1, 1, 10
            );
            return Message.ok(MessageType.QUESTION_LIST, req.getRequestId(), resp);
        });

        QuestionListResponse list = client.listQuestions("HTTP", "LTM", 1, "EASY", 1, 10).get(5, TimeUnit.SECONDS);
        assertEquals(1, list.getTotalRows());
        assertEquals(1, list.getQuestions().size());
    }

    @Test
    @DisplayName("Lecturer Exam creation, readiness, and monitoring via ExamClient")
    void testExamOperations() throws Exception {
        client.connect("localhost", mockServer.getPort()).get(5, TimeUnit.SECONDS);
        client.login("gv_lan", "pass").get(5, TimeUnit.SECONDS);

        // Mock EXAM_CREATE
        mockServer.setCustomHandler(MessageType.EXAM_CREATE.name(), req -> {
            ExamDto exam = new ExamDto("ex-new-1", "Thi giua ky", "LTM",
                    System.currentTimeMillis(), System.currentTimeMillis() + 3600000L, 45, 20, "OPEN");
            return Message.ok(MessageType.EXAM_CREATE, req.getRequestId(), exam);
        });

        CreateExamRequest createReq = new CreateExamRequest();
        createReq.setTitle("Thi giua ky");
        createReq.setSubject("LTM");
        createReq.setStartTime(System.currentTimeMillis());
        createReq.setEndTime(System.currentTimeMillis() + 3600000L);
        createReq.setDurationMinutes(45);
        createReq.setExamType("FIXED");
        createReq.setQuestionIds(List.of("q-1", "q-2"));
        createReq.setAssignedStudentIds(List.of("sv-1"));

        ExamDto examDto = client.createExam(createReq).get(5, TimeUnit.SECONDS);
        assertEquals("ex-new-1", examDto.getId());

        // Mock EXAM_READINESS
        mockServer.setCustomHandler(MessageType.EXAM_MONITOR.name(), req -> {
            Map<?, ?> p = (Map<?, ?>) req.getPayload();
            if (Boolean.TRUE.equals(p.get("checkReadiness"))) {
                ExamReadinessDto r = new ExamReadinessDto("ex-new-1", 4, 4, true, "Sẵn sàng 4/4 Server");
                return Message.ok(MessageType.EXAM_MONITOR, req.getRequestId(), r);
            }
            ExamMonitorDto m = new ExamMonitorDto("ex-new-1", "Thi giua ky", 50, 10, 20, 20, System.currentTimeMillis());
            return Message.ok(MessageType.EXAM_MONITOR, req.getRequestId(), m);
        });

        ExamReadinessDto readiness = client.getExamReadiness("ex-new-1").get(5, TimeUnit.SECONDS);
        assertTrue(readiness.isReady());
        assertEquals("Sẵn sàng 4/4 Server", readiness.getStatusText());

        ExamMonitorDto monitor = client.getExamMonitor("ex-new-1").get(5, TimeUnit.SECONDS);
        assertEquals(50, monitor.getTotalAssigned());
        assertEquals(20, monitor.getInProgressCount());

        // Mock GET_STATS
        mockServer.setCustomHandler(MessageType.GET_STATS.name(), req -> {
            ExamStatsDto stats = new ExamStatsDto("ex-new-1", 7.5, 9.8, 3.5, 20,
                    Map.of("< 5.0", 2, "8.5 - 10.0", 6), Map.of("q-1", 85.0));
            return Message.ok(MessageType.GET_STATS, req.getRequestId(), stats);
        });

        ExamStatsDto stats = client.getStats("ex-new-1").get(5, TimeUnit.SECONDS);
        assertEquals(7.5, stats.getAverageScore(), 0.01);
        assertEquals(9.8, stats.getHighestScore(), 0.01);
    }

    @Test
    @DisplayName("Admin User CRUD, Batch Import, and Log Query via ExamClient")
    void testAdminOperations() throws Exception {
        client.connect("localhost", mockServer.getPort()).get(5, TimeUnit.SECONDS);
        client.login("admin", "admin").get(5, TimeUnit.SECONDS);

        // Mock USER_CREATE
        mockServer.setCustomHandler(MessageType.USER_CREATE.name(), req -> {
            UserDto u = new UserDto("u-new", "sv_test", "Nguyen Test", "test@ptit.edu.vn",
                    UserRole.STUDENT, "D21CN1", "ACTIVE", 1);
            return Message.ok(MessageType.USER_CREATE, req.getRequestId(), u);
        });

        UserDto newUser = new UserDto();
        newUser.setUsername("sv_test");
        newUser.setPassword("Pass123@");
        newUser.setFullName("Nguyen Test");
        UserDto createdUser = client.createUser(newUser).get(5, TimeUnit.SECONDS);
        assertEquals("u-new", createdUser.getId());

        // Mock USER_BATCH
        mockServer.setCustomHandler(MessageType.USER_BATCH.name(), req -> {
            BatchRowError err = new BatchRowError(2, "sv_bad", "Mật khẩu trống");
            BatchUserResultDto res = new BatchUserResultDto(3, 2, 1, List.of(err));
            return Message.ok(MessageType.USER_BATCH, req.getRequestId(), res);
        });

        BatchUserResultDto batchRes = client.importUsersBatch(List.of(new UserDto(), new UserDto(), new UserDto()))
                .get(5, TimeUnit.SECONDS);
        assertEquals(3, batchRes.getTotalRows());
        assertEquals(2, batchRes.getImportedCount());
        assertEquals(1, batchRes.getFailedCount());
        assertEquals(2, batchRes.getErrors().get(0).getRowIndex());

        // Mock LOG_QUERY
        mockServer.setCustomHandler(MessageType.LOG_QUERY.name(), req -> {
            LogEntryDto entry = new LogEntryDto(System.currentTimeMillis(), "SYNC", "op-1", "origin-1",
                    "EXAM_PACKAGE", null, "DONE", "details");
            LogQueryResponse lq = new LogQueryResponse("SYNC", 1, 1, 50, List.of(entry));
            return Message.ok(MessageType.LOG_QUERY, req.getRequestId(), lq);
        });

        LogQueryResponse logs = client.queryLogs("SYNC", null, null, null, 1, 50).get(5, TimeUnit.SECONDS);
        assertEquals("SYNC", logs.getLogType());
        assertEquals(1, logs.getTotalRows());
        assertEquals("EXAM_PACKAGE", logs.getEntries().get(0).getAction());
    }

    @Test
    @DisplayName("Generic fetchAll: retrieves all paginated items across multiple pages with progress callback")
    void testGenericFetchAll() throws Exception {
        int totalItems = 350;
        List<String> masterData = new ArrayList<>();
        for (int i = 0; i < totalItems; i++) {
            masterData.add("Item " + (i + 1));
        }

        List<Double> progressList = new CopyOnWriteArrayList<>();

        // Generic page fetcher simulation
        CompletableFuture<List<String>> future = client.fetchAll((page, pageSize) -> {
            int from = (page - 1) * pageSize;
            if (from >= totalItems) {
                return CompletableFuture.completedFuture(new PageResponse<>(totalItems, page, pageSize, Collections.emptyList()));
            }
            int to = Math.min(from + pageSize, totalItems);
            List<String> sub = masterData.subList(from, to);
            return CompletableFuture.completedFuture(new PageResponse<>(totalItems, page, pageSize, sub));
        }, progressList::add);

        List<String> all = future.get(5, TimeUnit.SECONDS);
        assertEquals(350, all.size());
        assertEquals("Item 1", all.get(0));
        assertEquals("Item 350", all.get(349));

        assertFalse(progressList.isEmpty());
        assertEquals(1.0, progressList.get(progressList.size() - 1), 0.01);
    }
}
