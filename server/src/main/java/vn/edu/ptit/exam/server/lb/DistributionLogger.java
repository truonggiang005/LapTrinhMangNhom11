package vn.edu.ptit.exam.server.lb;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * In-memory distribution history logger for Load Balancer analytics and LOG_QUERY requests.
 */
public class DistributionLogger {

    public static final int MAX_ENTRIES = 10_000;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LogEntry {
        private final long timestamp;
        private final String requestId;
        private final String messageType;
        private final String userId;
        private final int targetServerId;
        private final long durationMs;
        private final String status;
        private final String error;

        public LogEntry(long timestamp, String requestId, String messageType, String userId,
                        int targetServerId, long durationMs, String status, String error) {
            this.timestamp = timestamp;
            this.requestId = requestId;
            this.messageType = messageType;
            this.userId = userId;
            this.targetServerId = targetServerId;
            this.durationMs = durationMs;
            this.status = status;
            this.error = error;
        }

        public long getTimestamp() { return timestamp; }
        public String getRequestId() { return requestId; }
        public String getMessageType() { return messageType; }
        public String getUserId() { return userId; }
        public int getTargetServerId() { return targetServerId; }
        public long getDurationMs() { return durationMs; }
        public String getStatus() { return status; }
        public String getError() { return error; }
    }

    private final List<LogEntry> entries = new CopyOnWriteArrayList<>();

    public void log(String requestId, String messageType, String userId, int targetServerId, long durationMs, String status, String error) {
        if (entries.size() >= MAX_ENTRIES) {
            // Trim oldest 1000 items
            for (int i = 0; i < 1000 && !entries.isEmpty(); i++) {
                entries.remove(0);
            }
        }
        entries.add(new LogEntry(System.currentTimeMillis(), requestId, messageType, userId, targetServerId, durationMs, status, error));
    }

    public List<LogEntry> query(String keyword, String userId, int page, int pageSize) {
        List<LogEntry> filtered = entries.stream()
                .filter(e -> {
                    if (userId != null && !userId.isBlank() && !userId.equalsIgnoreCase(e.getUserId())) {
                        return false;
                    }
                    if (keyword != null && !keyword.isBlank()) {
                        String kw = keyword.toLowerCase();
                        boolean match = (e.getMessageType() != null && e.getMessageType().toLowerCase().contains(kw))
                                || (e.getRequestId() != null && e.getRequestId().toLowerCase().contains(kw))
                                || (e.getUserId() != null && e.getUserId().toLowerCase().contains(kw));
                        if (!match) return false;
                    }
                    return true;
                })
                .collect(Collectors.toList());

        Collections.reverse(filtered); // Most recent first

        int fromIndex = Math.max(0, (page - 1) * pageSize);
        if (fromIndex >= filtered.size()) {
            return Collections.emptyList();
        }
        int toIndex = Math.min(fromIndex + pageSize, filtered.size());
        return new ArrayList<>(filtered.subList(fromIndex, toIndex));
    }

    public int getTotalCount() {
        return entries.size();
    }
}
