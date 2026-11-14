package vn.edu.ptit.exam.server.lb;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.edu.ptit.exam.common.dto.Message;
import vn.edu.ptit.exam.common.enums.MessageType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class StartExamQueueTest {

    private StartExamQueueManager queueManager;

    @BeforeEach
    void setUp() {
        // Limit max concurrent to 2 for deterministic queue testing
        queueManager = new StartExamQueueManager(2);
    }

    @AfterEach
    void tearDown() {
        if (queueManager != null) {
            queueManager.shutdown();
        }
    }

    @Test
    void testFifoQueueOrderingAndDispatch() {
        List<String> executedOrder = new ArrayList<>();

        Message req1 = Message.request(MessageType.START_EXAM, "REQ-1");
        Message req2 = Message.request(MessageType.START_EXAM, "REQ-2");
        Message req3 = Message.request(MessageType.START_EXAM, "REQ-3");
        Message req4 = Message.request(MessageType.START_EXAM, "REQ-4");

        // 1. Submit req1 and req2 -> within capacity (2), execute immediately
        int pos1 = queueManager.submitOrQueue(req1, null, m -> executedOrder.add(m.getRequestId()));
        int pos2 = queueManager.submitOrQueue(req2, null, m -> executedOrder.add(m.getRequestId()));

        assertEquals(0, pos1, "req1 should execute immediately");
        assertEquals(0, pos2, "req2 should execute immediately");
        assertEquals(2, queueManager.getActiveCount());
        assertEquals(0, queueManager.getQueueLength());
        assertEquals(List.of("REQ-1", "REQ-2"), executedOrder);

        // 2. Submit req3 and req4 -> capacity full (2/2) -> enqueued FIFO
        int pos3 = queueManager.submitOrQueue(req3, null, m -> executedOrder.add(m.getRequestId()));
        int pos4 = queueManager.submitOrQueue(req4, null, m -> executedOrder.add(m.getRequestId()));

        assertEquals(1, pos3, "req3 should be enqueued at position 1");
        assertEquals(2, pos4, "req4 should be enqueued at position 2");
        assertEquals(2, queueManager.getQueueLength());
        assertEquals(2, queueManager.getActiveCount());

        // 3. Complete req1 -> slot freed -> req3 (head of FIFO) dispatched
        queueManager.onStartExamCompleted();
        assertEquals(1, queueManager.getQueueLength());
        assertEquals(2, queueManager.getActiveCount());
        assertEquals(List.of("REQ-1", "REQ-2", "REQ-3"), executedOrder);

        // 4. Complete req2 -> slot freed -> req4 dispatched
        queueManager.onStartExamCompleted();
        assertEquals(0, queueManager.getQueueLength());
        assertEquals(2, queueManager.getActiveCount());
        assertEquals(List.of("REQ-1", "REQ-2", "REQ-3", "REQ-4"), executedOrder);

        // 5. Complete remaining requests -> active count drops to 0
        queueManager.onStartExamCompleted();
        queueManager.onStartExamCompleted();
        assertEquals(0, queueManager.getActiveCount());
    }
}
