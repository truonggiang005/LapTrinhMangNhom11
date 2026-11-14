package vn.edu.ptit.exam.server.lb;

import vn.edu.ptit.exam.common.dto.ExamTicket;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exam registry on Load Balancer.
 * Implements Invariant 8: Stickiness of exam attempts to their assigned backend server.
 * Also holds ExamTicket and attempt associations for seamless failover (Luồng 8).
 */
public class ExamRegistry {

    // attemptId -> serverId
    private final Map<String, Integer> attemptToServerMap = new ConcurrentHashMap<>();
    // userId#examId -> serverId
    private final Map<String, Integer> userExamToServerMap = new ConcurrentHashMap<>();
    // attemptId -> userId#examId
    private final Map<String, String> attemptToUserExamKey = new ConcurrentHashMap<>();
    // attemptId -> ExamTicket
    private final Map<String, ExamTicket> attemptTicketMap = new ConcurrentHashMap<>();
    // attemptId -> userId
    private final Map<String, String> attemptToUserMap = new ConcurrentHashMap<>();

    private String buildUserExamKey(String userId, String examId) {
        return (userId != null ? userId.trim() : "") + "#" + (examId != null ? examId.trim() : "");
    }

    public void registerExam(String userId, String examId, String attemptId, int serverId) {
        registerExam(userId, examId, attemptId, serverId, null);
    }

    public void registerExam(String userId, String examId, String attemptId, int serverId, ExamTicket ticket) {
        if (attemptId != null) {
            attemptToServerMap.put(attemptId, serverId);
            if (ticket != null) {
                attemptTicketMap.put(attemptId, ticket);
            }
            if (userId != null) {
                attemptToUserMap.put(attemptId, userId);
            }
        }
        if (userId != null && examId != null) {
            String key = buildUserExamKey(userId, examId);
            userExamToServerMap.put(key, serverId);
            if (attemptId != null) {
                attemptToUserExamKey.put(attemptId, key);
            }
        }
    }

    public Optional<Integer> getServerForAttempt(String attemptId) {
        if (attemptId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(attemptToServerMap.get(attemptId));
    }

    public Optional<Integer> getServerForUserExam(String userId, String examId) {
        if (userId == null || examId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(userExamToServerMap.get(buildUserExamKey(userId, examId)));
    }

    public ExamTicket getTicket(String attemptId) {
        return attemptId != null ? attemptTicketMap.get(attemptId) : null;
    }

    public String getUserForAttempt(String attemptId) {
        return attemptId != null ? attemptToUserMap.get(attemptId) : null;
    }

    public List<String> getAttemptsForServer(int serverId) {
        List<String> list = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : attemptToServerMap.entrySet()) {
            if (entry.getValue() != null && entry.getValue() == serverId) {
                list.add(entry.getKey());
            }
        }
        return list;
    }

    public void reassignServer(String attemptId, int newServerId) {
        if (attemptId != null) {
            attemptToServerMap.put(attemptId, newServerId);
            String key = attemptToUserExamKey.get(attemptId);
            if (key != null) {
                userExamToServerMap.put(key, newServerId);
            }
        }
    }

    public void removeAttempt(String attemptId) {
        if (attemptId != null) {
            attemptToServerMap.remove(attemptId);
            attemptTicketMap.remove(attemptId);
            attemptToUserMap.remove(attemptId);
            String key = attemptToUserExamKey.remove(attemptId);
            if (key != null) {
                userExamToServerMap.remove(key);
            }
        }
    }

    public int getActiveAttemptsCount() {
        return attemptToServerMap.size();
    }
}
