package vn.edu.ptit.exam.server.lb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.common.dto.Message;
import vn.edu.ptit.exam.common.enums.MessageType;
import vn.edu.ptit.exam.common.util.JsonUtil;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * FIFO Queue manager for START_EXAM requests according to Section 5 / Luồng 3.
 * Maximum 50 concurrent in-flight START_EXAM requests.
 * Excess requests are queued and updated with their position every 3 seconds.
 */
public class StartExamQueueManager {

    private static final Logger log = LoggerFactory.getLogger(StartExamQueueManager.class);
    public static final int DEFAULT_MAX_CONCURRENT = 50;

    public static class QueuedItem {
        final Message request;
        final BufferedWriter clientWriter;
        final Consumer<Message> executionCallback;
        final long enqueuedTime;

        public QueuedItem(Message request, BufferedWriter clientWriter, Consumer<Message> executionCallback) {
            this.request = request;
            this.clientWriter = clientWriter;
            this.executionCallback = executionCallback;
            this.enqueuedTime = System.currentTimeMillis();
        }
    }

    private final int maxConcurrent;
    private final AtomicInteger activeCount = new AtomicInteger(0);
    private final Queue<QueuedItem> fifoQueue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService queueNotifier = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "lb-queue-notifier");
        t.setDaemon(true);
        return t;
    });

    public StartExamQueueManager() {
        this(DEFAULT_MAX_CONCURRENT);
    }

    public StartExamQueueManager(int maxConcurrent) {
        this.maxConcurrent = maxConcurrent;
        // Schedule periodic 3s position broadcast to waiting clients
        queueNotifier.scheduleAtFixedRate(this::broadcastQueuePositions, 3, 3, TimeUnit.SECONDS);
    }

    /**
     * Submit a START_EXAM request.
     * If capacity is available, executes immediately.
     * Otherwise, enqueues FIFO and returns current queue position.
     *
     * @return 0 if executed immediately, or queue position (>0) if queued.
     */
    public synchronized int submitOrQueue(Message request, BufferedWriter clientWriter, Consumer<Message> executionCallback) {
        if (activeCount.get() < maxConcurrent) {
            activeCount.incrementAndGet();
            log.debug("START_EXAM capacity available ({}/{}). Executing immediately.", activeCount.get(), maxConcurrent);
            executionCallback.accept(request);
            return 0;
        }

        QueuedItem item = new QueuedItem(request, clientWriter, executionCallback);
        fifoQueue.offer(item);
        int position = fifoQueue.size();
        log.info("START_EXAM concurrent limit ({}) reached. Request {} enqueued at position {}", maxConcurrent, request.getRequestId(), position);

        // Send immediate QUEUED confirmation to client
        sendQueuedNotice(item, position);
        return position;
    }

    /**
     * Call when an in-flight START_EXAM completes. Releases a slot and processes the next queued request.
     */
    public synchronized void onStartExamCompleted() {
        activeCount.updateAndGet(c -> Math.max(0, c - 1));
        log.debug("START_EXAM slot released. Active count: {}/{}", activeCount.get(), maxConcurrent);

        QueuedItem next = fifoQueue.poll();
        if (next != null) {
            activeCount.incrementAndGet();
            log.info("Dispatching queued START_EXAM request {} from head of FIFO queue", next.request.getRequestId());
            next.executionCallback.accept(next.request);
        }
    }

    private void broadcastQueuePositions() {
        if (fifoQueue.isEmpty()) {
            return;
        }

        List<QueuedItem> currentQueue = new ArrayList<>(fifoQueue);
        int pos = 1;
        for (QueuedItem item : currentQueue) {
            sendQueuedNotice(item, pos);
            pos++;
        }
    }

    private void sendQueuedNotice(QueuedItem item, int position) {
        try {
            if (item.clientWriter != null) {
                int waitSec = position * 3;
                Message queuedMsg = Message.ok(MessageType.QUEUED, item.request.getRequestId(), Map.of(
                        "position", position,
                        "estimatedWaitSeconds", waitSec
                ));
                item.clientWriter.write(JsonUtil.toJson(queuedMsg) + "\n");
                item.clientWriter.flush();
            }
        } catch (IOException e) {
            log.warn("Failed to send QUEUED notice for request {}: {}", item.request.getRequestId(), e.getMessage());
        }
    }

    public int getQueueLength() {
        return fifoQueue.size();
    }

    public int getActiveCount() {
        return activeCount.get();
    }

    public void shutdown() {
        queueNotifier.shutdown();
        fifoQueue.clear();
    }
}
