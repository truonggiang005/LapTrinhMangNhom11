package vn.edu.ptit.exam.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.edu.ptit.exam.common.config.Config;
import vn.edu.ptit.exam.common.dto.AnswerItemDto;
import vn.edu.ptit.exam.common.dto.AuthToken;
import vn.edu.ptit.exam.common.dto.Message;
import vn.edu.ptit.exam.common.enums.ErrorCode;
import vn.edu.ptit.exam.common.enums.MessageType;
import vn.edu.ptit.exam.common.enums.UserRole;
import vn.edu.ptit.exam.common.model.Attempt;
import vn.edu.ptit.exam.common.util.HmacUtil;
import vn.edu.ptit.exam.server.dao.AttemptDao;
import vn.edu.ptit.exam.server.handler.SaveAnswersHandler;
import vn.edu.ptit.exam.server.net.SocketContext;

import java.net.Socket;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SaveAnswersHandlerTest {

    private AttemptDao mockAttemptDao;
    private SaveAnswersHandler saveAnswersHandler;
    private SocketContext dummyContext;

    private final Map<String, Attempt> attemptStore = new HashMap<>();
    private final List<AnswerItemDto> savedBatch = new ArrayList<>();
    private String studentToken;
    private final String secretKey = Config.getInstance().getHmacSecretKey();

    @BeforeEach
    void setUp() {
        attemptStore.clear();
        savedBatch.clear();

        long now = System.currentTimeMillis();
        AuthToken token = new AuthToken("u-sv-001", "sv_01", "Nguyen Van A", UserRole.STUDENT, now, now + 4 * 3600 * 1000);
        studentToken = HmacUtil.createToken(token, secretKey);

        Attempt attempt = new Attempt();
        attempt.setId("att-001");
        attempt.setExamId("exam-001");
        attempt.setStudentId("u-sv-001");
        attempt.setStartTime(new Timestamp(now - 10000));
        attempt.setDeadline(new Timestamp(now + 60000)); // 1 min left
        attempt.setStatus("IN_PROGRESS");
        attemptStore.put("att-001", attempt);

        mockAttemptDao = new AttemptDao(null, null) {
            @Override
            public Attempt findAttemptById(String attemptId) {
                return attemptStore.get(attemptId);
            }

            @Override
            public int saveAnswersBatch(String attemptId, long seq, List<AnswerItemDto> answers) {
                savedBatch.addAll(answers);
                return answers.size();
            }
        };

        saveAnswersHandler = new SaveAnswersHandler(mockAttemptDao);
        dummyContext = new SocketContext("test-save-ctx", new Socket());
    }

    @Test
    @DisplayName("Should successfully save answers when seq is increasing")
    void testSaveAnswersSuccess() throws Exception {
        Message req = Message.request(MessageType.SAVE_ANSWERS, "req-save-1");
        req.setToken(studentToken);
        req.setAttemptId("att-001");
        req.setSeq(10L);
        req.setPayload(Map.of(
                "attemptId", "att-001",
                "answers", List.of(new AnswerItemDto("q-001", "opt-001-A", false))
        ));

        Message resp = saveAnswersHandler.handle(req, dummyContext);
        assertEquals("OK", resp.getStatus());
        assertEquals(1, savedBatch.size());
        assertEquals("opt-001-A", savedBatch.get(0).getSelectedOptionId());
    }

    @Test
    @DisplayName("Should safely ignore late packet with older seq without error")
    void testOlderSeqDropped() throws Exception {
        // Send packet with seq = 20
        Message req1 = Message.request(MessageType.SAVE_ANSWERS, "req-save-1");
        req1.setToken(studentToken);
        req1.setAttemptId("att-001");
        req1.setSeq(20L);
        req1.setPayload(Map.of(
                "attemptId", "att-001",
                "answers", List.of(new AnswerItemDto("q-001", "opt-001-B", false))
        ));
        saveAnswersHandler.handle(req1, dummyContext);
        assertEquals(1, savedBatch.size());

        // Send delayed packet with seq = 15 (older)
        Message reqOld = Message.request(MessageType.SAVE_ANSWERS, "req-save-old");
        reqOld.setToken(studentToken);
        reqOld.setAttemptId("att-001");
        reqOld.setSeq(15L);
        reqOld.setPayload(Map.of(
                "attemptId", "att-001",
                "answers", List.of(new AnswerItemDto("q-001", "opt-001-A", false))
        ));
        Message respOld = saveAnswersHandler.handle(reqOld, dummyContext);

        // Still returns OK, but savedCount is 0, does not overwrite newer answer
        assertEquals("OK", respOld.getStatus());
        assertEquals(1, savedBatch.size()); // no new elements added
    }

    @Test
    @DisplayName("Should reject SAVE_ANSWERS when deadline + 5s tolerance has expired")
    void testExpiredDeadline() throws Exception {
        // Set deadline to 10 seconds in the past
        Attempt a = attemptStore.get("att-001");
        a.setDeadline(new Timestamp(System.currentTimeMillis() - 10000));

        Message req = Message.request(MessageType.SAVE_ANSWERS, "req-save-expired");
        req.setToken(studentToken);
        req.setAttemptId("att-001");
        req.setSeq(30L);
        req.setPayload(Map.of("attemptId", "att-001"));

        Message resp = saveAnswersHandler.handle(req, dummyContext);
        assertEquals("ERROR", resp.getStatus());
        assertEquals(ErrorCode.ATTEMPT_EXPIRED.getCode(), resp.getCode());
    }
}
