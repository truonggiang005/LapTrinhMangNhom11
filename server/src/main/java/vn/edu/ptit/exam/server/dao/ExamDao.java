package vn.edu.ptit.exam.server.dao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.common.config.Config;
import vn.edu.ptit.exam.common.dto.*;
import vn.edu.ptit.exam.common.enums.ErrorCode;
import vn.edu.ptit.exam.server.db.DatabaseManager;
import vn.edu.ptit.exam.server.exception.ExamServerException;
import vn.edu.ptit.exam.server.sync.SyncLogWriter;
import vn.edu.ptit.exam.server.sync.SyncPeerStateDao;
import vn.edu.ptit.exam.server.sync.model.SyncPeerStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

public class ExamDao {

    private static final Logger log = LoggerFactory.getLogger(ExamDao.class);
    private final DatabaseManager dbManager;

    public ExamDao(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    /**
     * Lists exams assigned to a student with keyword search, status filtering, and pagination.
     */
    public ExamListResponse listExamsForStudent(String studentId, String keyword, String statusFilter,
                                                int page, int pageSize, long serverTime) throws SQLException {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT e.id, e.title, e.subject, e.duration_minutes, e.start_time, e.end_time, e.total_questions, e.show_score_type, ")
           .append("a.id AS attempt_id, a.status AS attempt_status, a.deadline, a.score, ")
           .append("(SELECT rr.status FROM retake_requests rr WHERE rr.exam_id = e.id AND rr.student_id = ea.student_id ORDER BY rr.created_at DESC LIMIT 1) AS retake_status ")
           .append("FROM exam_assignments ea ")
           .append("JOIN exams e ON ea.exam_id = e.id ")
           .append("LEFT JOIN attempts a ON a.exam_id = e.id AND a.student_id = ea.student_id ")
           .append("WHERE ea.student_id = ? ");

        List<Object> params = new ArrayList<>();
        params.add(studentId);

        if (keyword != null && !keyword.trim().isEmpty()) {
            sql.append("AND (LOWER(e.title) LIKE ? OR LOWER(e.subject) LIKE ?) ");
            String searchPattern = "%" + keyword.trim().toLowerCase() + "%";
            params.add(searchPattern);
            params.add(searchPattern);
        }

        sql.append("ORDER BY e.start_time DESC");

        List<ExamItemDto> allItems = new ArrayList<>();
        long lateTolerance = Config.getInstance().getExamLateToleranceMs();

        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    String title = rs.getString("title");
                    String subject = rs.getString("subject");
                    int durationMinutes = rs.getInt("duration_minutes");
                    long startEpoch = rs.getTimestamp("start_time").getTime();
                    long endEpoch = rs.getTimestamp("end_time").getTime();
                    int totalQuestions = rs.getInt("total_questions");
                    String showScoreType = rs.getString("show_score_type");

                    String attemptId = rs.getString("attempt_id");
                    String attemptStatus = rs.getString("attempt_status");
                    String retakeStatus = rs.getString("retake_status");
                    Timestamp deadlineTs = rs.getTimestamp("deadline");
                    Object scoreObj = rs.getObject("score");
                    Double score = null;
                    if (scoreObj instanceof Number) {
                        score = ((Number) scoreObj).doubleValue();
                    }

                    String computedStatus;
                    Long remainingSeconds = null;

                    if ("SUBMITTED".equalsIgnoreCase(attemptStatus)) {
                        computedStatus = "SUBMITTED";
                    } else if ("IN_PROGRESS".equalsIgnoreCase(attemptStatus)) {
                        long deadlineEpoch = deadlineTs != null ? deadlineTs.getTime() : (startEpoch + durationMinutes * 60000L);
                        if (serverTime <= deadlineEpoch + lateTolerance) {
                            computedStatus = "IN_PROGRESS";
                            remainingSeconds = Math.max(0, (deadlineEpoch - serverTime) / 1000);
                        } else {
                            computedStatus = "CLOSED";
                        }
                    } else if (serverTime < startEpoch) {
                        computedStatus = "NOT_OPEN";
                    } else if (serverTime > endEpoch) {
                        computedStatus = "CLOSED";
                    } else {
                        computedStatus = "OPEN";
                    }

                    Double displayScore = null;
                    if ("SUBMITTED".equalsIgnoreCase(computedStatus) && score != null) {
                        if ("IMMEDIATE".equalsIgnoreCase(showScoreType) ||
                            ("AFTER_EXAM_CLOSED".equalsIgnoreCase(showScoreType) && serverTime > endEpoch)) {
                            displayScore = score;
                        }
                    }

                    ExamItemDto item = new ExamItemDto(id, title, subject, durationMinutes, startEpoch, endEpoch, computedStatus, totalQuestions);
                    item.setAttemptId(attemptId);
                    item.setRemainingSeconds(remainingSeconds);
                    item.setScore(displayScore);
                    item.setRetakeStatus(retakeStatus);

                    allItems.add(item);
                }
            }
        }

        List<ExamItemDto> filteredItems = allItems;
        if (statusFilter != null && !statusFilter.trim().isEmpty() && !"ALL".equalsIgnoreCase(statusFilter)) {
            filteredItems = allItems.stream()
                    .filter(e -> e.getStatus().equalsIgnoreCase(statusFilter.trim()))
                    .collect(Collectors.toList());
        }

        int total = filteredItems.size();
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, pageSize);
        int fromIndex = (safePage - 1) * safePageSize;

        List<ExamItemDto> pagedItems;
        if (fromIndex >= total) {
            pagedItems = new ArrayList<>();
        } else {
            int toIndex = Math.min(fromIndex + safePageSize, total);
            pagedItems = filteredItems.subList(fromIndex, toIndex);
        }

        return new ExamListResponse(total, safePage, safePageSize, pagedItems);
    }

    // =========================================================================
    // EXAM MANAGEMENT (CREATE, MONITOR, SCORES, STATS, READINESS)
    // =========================================================================

    /**
     * Creates an entire Exam Package atomically:
     * Validates times, questions, and students.
     * Inserts into exams, exam_questions, exam_assignments.
     * Logs an atomic EXAM_PACKAGE sync_log entry in the same transaction.
     */
    public ExamDto createExamPackage(CreateExamRequest req, String createdBy,
                                     SyncLogWriter syncLogWriter, QuestionDao questionDao)
            throws SQLException, ExamServerException {
        // 1. Validation
        if (req.getTitle() == null || req.getTitle().trim().isEmpty()) {
            throw new ExamServerException(ErrorCode.BAD_REQUEST, "Tên ca thi không được để trống");
        }
        if (req.getSubject() == null || req.getSubject().trim().isEmpty()) {
            throw new ExamServerException(ErrorCode.BAD_REQUEST, "Môn thi không được để trống");
        }
        if (req.getStartTime() <= 0 || req.getEndTime() <= 0) {
            throw new ExamServerException(ErrorCode.BAD_REQUEST, "Thời gian mở và đóng ca thi không hợp lệ");
        }
        if (req.getStartTime() >= req.getEndTime()) {
            throw new ExamServerException(ErrorCode.BAD_REQUEST, "Giờ đóng ca thi phải sau giờ mở ca thi");
        }
        if (req.getDurationMinutes() <= 0) {
            throw new ExamServerException(ErrorCode.BAD_REQUEST, "Thời lượng thi phải lớn hơn 0");
        }
        long windowDurationMs = req.getEndTime() - req.getStartTime();
        if (req.getDurationMinutes() * 60000L > windowDurationMs) {
            throw new ExamServerException(ErrorCode.BAD_REQUEST, "Thời lượng làm bài không được vượt quá khoảng thời gian mở ca thi");
        }

        // 2. Determine questions
        List<String> finalQuestionIds = new ArrayList<>();
        boolean isRandom = "RANDOM_STRUCTURE".equalsIgnoreCase(req.getExamType());

        if (isRandom) {
            if (req.getStructureRules() == null || req.getStructureRules().isEmpty()) {
                throw new ExamServerException(ErrorCode.BAD_REQUEST, "Cấu trúc đề ngẫu nhiên không có quy tắc nào");
            }

            for (CreateExamRequest.StructureRule rule : req.getStructureRules()) {
                int available = questionDao.countAvailableQuestions(req.getSubject(), rule.getChapter(), rule.getDifficulty());
                if (available < rule.getCount()) {
                    throw new ExamServerException(ErrorCode.BAD_REQUEST,
                            String.format("Ngân hàng không đủ câu hỏi cho cấu trúc đề: chương %d, độ khó %s (có %d, cần %d)",
                                    rule.getChapter() != null ? rule.getChapter() : 0,
                                    rule.getDifficulty() != null ? rule.getDifficulty() : "ALL",
                                    available, rule.getCount()));
                }
                List<String> sampled = questionDao.findRandomQuestionIds(req.getSubject(), rule.getChapter(), rule.getDifficulty(), rule.getCount());
                finalQuestionIds.addAll(sampled);
            }
        } else {
            // Fixed questions
            if (req.getQuestionIds() == null || req.getQuestionIds().isEmpty()) {
                throw new ExamServerException(ErrorCode.BAD_REQUEST, "Đề thi cố định phải có danh sách câu hỏi");
            }
            finalQuestionIds.addAll(req.getQuestionIds());
            // Verify all questions exist and not deleted
            for (String qId : finalQuestionIds) {
                QuestionDto q = questionDao.findQuestionById(qId);
                if (q == null) {
                    throw new ExamServerException(ErrorCode.BAD_REQUEST, "Câu hỏi " + qId + " không tồn tại hoặc đã bị xóa");
                }
            }
        }

        if (finalQuestionIds.isEmpty()) {
            throw new ExamServerException(ErrorCode.BAD_REQUEST, "Ca thi không có câu hỏi nào");
        }

        // 3. Validate assigned students
        if (req.getAssignedStudentIds() == null || req.getAssignedStudentIds().isEmpty()) {
            throw new ExamServerException(ErrorCode.BAD_REQUEST, "Chưa phân công sinh viên nào vào ca thi");
        }

        try (Connection conn = dbManager.getConnection()) {
            String checkStudentSql = "SELECT id FROM users WHERE id = ? AND role = 'STUDENT'";
            try (PreparedStatement ps = conn.prepareStatement(checkStudentSql)) {
                for (String studentId : req.getAssignedStudentIds()) {
                    ps.setString(1, studentId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new ExamServerException(ErrorCode.BAD_REQUEST, "Sinh viên không tồn tại trong hệ thống: " + studentId);
                        }
                    }
                }
            }
        }

        // 4. Atomically persist Exam, Questions, Assignments, and SyncLog
        String examId = req.getId() != null ? req.getId() : UUID.randomUUID().toString();
        int totalQuestions = finalQuestionIds.size();
        double maxScore = req.getMaxScore() > 0 ? req.getMaxScore() : 10.0;
        BigDecimal pointPerQuestion = BigDecimal.valueOf(maxScore / totalQuestions).setScale(2, RoundingMode.HALF_UP);

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Insert Exam
                String insertExamSql = "INSERT INTO exams (id, title, subject, duration_minutes, start_time, end_time, " +
                                       "exam_type, total_questions, max_score, show_score_type, status, version, created_by, created_at, updated_at) " +
                                       "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', 1, ?, NOW(), NOW())";
                try (PreparedStatement ps = conn.prepareStatement(insertExamSql)) {
                    ps.setString(1, examId);
                    ps.setString(2, req.getTitle());
                    ps.setString(3, req.getSubject());
                    ps.setInt(4, req.getDurationMinutes());
                    ps.setTimestamp(5, new Timestamp(req.getStartTime()));
                    ps.setTimestamp(6, new Timestamp(req.getEndTime()));
                    ps.setString(7, req.getExamType());
                    ps.setInt(8, totalQuestions);
                    ps.setDouble(9, maxScore);
                    ps.setString(10, req.getShowScoreType());
                    ps.setString(11, createdBy);
                    ps.executeUpdate();
                }

                // Insert exam_questions
                String insertEqSql = "INSERT INTO exam_questions (id, exam_id, question_id, question_order, points) VALUES (?, ?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(insertEqSql)) {
                    int order = 1;
                    for (String qId : finalQuestionIds) {
                        ps.setString(1, UUID.randomUUID().toString());
                        ps.setString(2, examId);
                        ps.setString(3, qId);
                        ps.setInt(4, order++);
                        ps.setBigDecimal(5, pointPerQuestion);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }

                // Insert exam_assignments
                String insertAssignSql = "INSERT INTO exam_assignments (id, exam_id, student_id, assigned_at, version) VALUES (?, ?, ?, NOW(), 1)";
                try (PreparedStatement ps = conn.prepareStatement(insertAssignSql)) {
                    for (String studentId : req.getAssignedStudentIds()) {
                        ps.setString(1, UUID.randomUUID().toString());
                        ps.setString(2, examId);
                        ps.setString(3, studentId);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }

                // Build atomic EXAM_PACKAGE sync payload for peers
                Map<String, Object> examPayload = new HashMap<>();
                Map<String, Object> examData = new HashMap<>();
                examData.put("id", examId);
                examData.put("title", req.getTitle());
                examData.put("subject", req.getSubject());
                examData.put("durationMinutes", req.getDurationMinutes());
                examData.put("startTime", req.getStartTime());
                examData.put("endTime", req.getEndTime());
                examData.put("examType", req.getExamType());
                examData.put("totalQuestions", totalQuestions);
                examData.put("maxScore", maxScore);
                examData.put("showScoreType", req.getShowScoreType());
                examData.put("status", "OPEN");
                examData.put("version", 1);
                examPayload.put("exam", examData);

                List<Map<String, Object>> questionsList = new ArrayList<>();
                int qOrder = 1;
                for (String qId : finalQuestionIds) {
                    Map<String, Object> qm = new HashMap<>();
                    qm.put("id", UUID.randomUUID().toString());
                    qm.put("questionId", qId);
                    qm.put("questionOrder", qOrder++);
                    qm.put("points", pointPerQuestion.doubleValue());
                    questionsList.add(qm);
                }
                examPayload.put("questions", questionsList);

                List<Map<String, Object>> assignmentsList = new ArrayList<>();
                for (String studentId : req.getAssignedStudentIds()) {
                    Map<String, Object> am = new HashMap<>();
                    am.put("id", UUID.randomUUID().toString());
                    am.put("studentId", studentId);
                    assignmentsList.add(am);
                }
                examPayload.put("assignments", assignmentsList);

                // Invariant 3: Write sync_log in same transaction
                if (syncLogWriter != null) {
                    syncLogWriter.writeSyncLog(conn, "exams", examId, "EXAM_PACKAGE", 1, examPayload);
                }

                conn.commit();
                log.info("Created exam package [id={}, title={}, questions={}, students={}]",
                        examId, req.getTitle(), totalQuestions, req.getAssignedStudentIds().size());

                ExamDto result = new ExamDto(examId, req.getTitle(), req.getSubject(),
                        req.getStartTime(), req.getEndTime(), req.getDurationMinutes(), totalQuestions, "OPEN");
                result.setQuestionCount(totalQuestions);
                return result;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /**
     * Checks replication readiness of an exam package across the 4 servers.
     */
    public ExamReadinessDto getExamReadiness(String examId, SyncPeerStateDao syncPeerStateDao, String localServerId)
            throws SQLException {
        int totalServers = 4;
        int replicatedServers = 1; // Local server always has it if found locally

        // Check if exam exists locally
        String sql = "SELECT origin, seq FROM sync_log WHERE table_name = 'exams' AND record_id = ? LIMIT 1";
        String origin = null;
        Long seq = null;

        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, examId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    origin = rs.getString("origin");
                    seq = rs.getLong("seq");
                }
            }
        }

        if (origin != null && seq != null && syncPeerStateDao != null) {
            Map<String, SyncPeerStatus> peers = syncPeerStateDao.getAllPeerStates();
            for (SyncPeerStatus peer : peers.values()) {
                if (peer.getLastAckedSeq() >= seq || peer.getLastReceivedSeq() >= seq) {
                    replicatedServers++;
                }
            }
        } else {
            // If exam was applied from another server, check if peers are synced
            if (syncPeerStateDao != null) {
                Map<String, SyncPeerStatus> peers = syncPeerStateDao.getAllPeerStates();
                for (SyncPeerStatus peer : peers.values()) {
                    if ("ACTIVE".equalsIgnoreCase(peer.getStatus())) {
                        replicatedServers++;
                    }
                }
            }
        }

        replicatedServers = Math.min(replicatedServers, totalServers);
        boolean ready = (replicatedServers == totalServers);
        String statusText = String.format("Sẵn sàng %d/%d Server", replicatedServers, totalServers);

        return new ExamReadinessDto(examId, replicatedServers, totalServers, ready, statusText);
    }

    /**
     * Real-time monitoring metrics for ongoing exam: assigned, not started, in-progress, submitted.
     */
    public ExamMonitorDto getExamMonitor(String examId) throws SQLException {
        String title = "";
        String titleSql = "SELECT title FROM exams WHERE id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(titleSql)) {
            ps.setString(1, examId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    title = rs.getString("title");
                }
            }
        }

        int totalAssigned = 0;
        String countAssignedSql = "SELECT COUNT(*) FROM exam_assignments WHERE exam_id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(countAssignedSql)) {
            ps.setString(1, examId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    totalAssigned = rs.getInt(1);
                }
            }
        }

        int inProgressCount = 0;
        int submittedCount = 0;
        String statusSql = "SELECT status, COUNT(*) FROM attempts WHERE exam_id = ? GROUP BY status";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(statusSql)) {
            ps.setString(1, examId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String st = rs.getString(1);
                    int count = rs.getInt(2);
                    if ("IN_PROGRESS".equalsIgnoreCase(st)) {
                        inProgressCount += count;
                    } else if ("SUBMITTED".equalsIgnoreCase(st) || "EXPIRED".equalsIgnoreCase(st)) {
                        submittedCount += count;
                    }
                }
            }
        }

        int notStartedCount = Math.max(0, totalAssigned - inProgressCount - submittedCount);
        long serverTime = System.currentTimeMillis();

        return new ExamMonitorDto(examId, title, totalAssigned, notStartedCount, inProgressCount, submittedCount, serverTime);
    }

    /**
     * Retrieves student score table with pagination, keyword search, and finalized status.
     */
    public ScoreListResponse getScores(String examId, String keyword, int page, int pageSize, String sortBy) throws SQLException {
        boolean isFinalized = false;
        String examSql = "SELECT status FROM exams WHERE id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(examSql)) {
            ps.setString(1, examId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    isFinalized = "FINALIZED".equalsIgnoreCase(rs.getString("status"));
                }
            }
        }

        StringBuilder whereClause = new StringBuilder("WHERE ea.exam_id = ? ");
        List<Object> params = new ArrayList<>();
        params.add(examId);

        if (keyword != null && !keyword.trim().isEmpty()) {
            whereClause.append("AND (LOWER(u.username) LIKE ? OR LOWER(u.full_name) LIKE ? OR LOWER(u.class_name) LIKE ?) ");
            String pat = "%" + keyword.trim().toLowerCase() + "%";
            params.add(pat);
            params.add(pat);
            params.add(pat);
        }

        // Count total
        String countSql = "SELECT COUNT(*) FROM exam_assignments ea " +
                          "JOIN users u ON ea.student_id = u.id " + whereClause;
        int totalRows = 0;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(countSql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    totalRows = rs.getInt(1);
                }
            }
        }

        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, pageSize);
        int offset = (safePage - 1) * safePageSize;

        String querySql = "SELECT ea.student_id, u.username, u.full_name, u.class_name, " +
                          "a.score, a.correct_count, a.total_questions, a.submit_time, a.is_late, a.status " +
                          "FROM exam_assignments ea " +
                          "JOIN users u ON ea.student_id = u.id " +
                          "LEFT JOIN attempts a ON a.exam_id = ea.exam_id AND a.student_id = ea.student_id " +
                          whereClause +
                          "ORDER BY u.username ASC LIMIT ? OFFSET ?";

        List<ScoreItemDto> list = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(querySql)) {
            int idx = 1;
            for (Object param : params) {
                ps.setObject(idx++, param);
            }
            ps.setInt(idx++, safePageSize);
            ps.setInt(idx, offset);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String studentId = rs.getString("student_id");
                    String username = rs.getString("username");
                    String fullName = rs.getString("full_name");
                    Double score = (Double) rs.getObject("score");
                    Integer correctCount = (Integer) rs.getObject("correct_count");
                    Integer totalQuestions = (Integer) rs.getObject("total_questions");
                    Timestamp submitTime = rs.getTimestamp("submit_time");
                    boolean isLate = rs.getBoolean("is_late");
                    String attemptStatus = rs.getString("status");

                    String displayStatus = attemptStatus != null ? attemptStatus : "NOT_STARTED";
                    Long submittedEpoch = submitTime != null ? submitTime.getTime() : null;

                    ScoreItemDto item = new ScoreItemDto(studentId, username, fullName, score,
                            correctCount, totalQuestions, submittedEpoch, isLate, displayStatus);
                    list.add(item);
                }
            }
        }

        return new ScoreListResponse(list, totalRows, safePage, safePageSize, isFinalized);
    }

    /**
     * Aggregates exam statistics: average, min, max, score distribution, and per-question accuracy.
     */
    public ExamStatsDto getStats(String examId) throws SQLException {
        Double avgScore = null;
        Double highestScore = null;
        Double lowestScore = null;
        int totalAttempts = 0;

        Map<String, Integer> scoreDistribution = new LinkedHashMap<>();
        scoreDistribution.put("< 5.0", 0);
        scoreDistribution.put("5.0 - < 7.0", 0);
        scoreDistribution.put("7.0 - < 8.5", 0);
        scoreDistribution.put("8.5 - 10.0", 0);

        String scoreSql = "SELECT score FROM attempts WHERE exam_id = ? AND status IN ('SUBMITTED', 'EXPIRED') AND score IS NOT NULL";
        List<Double> scores = new ArrayList<>();

        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(scoreSql)) {
            ps.setString(1, examId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double s = rs.getDouble("score");
                    scores.add(s);

                    if (s < 5.0) {
                        scoreDistribution.put("< 5.0", scoreDistribution.get("< 5.0") + 1);
                    } else if (s < 7.0) {
                        scoreDistribution.put("5.0 - < 7.0", scoreDistribution.get("5.0 - < 7.0") + 1);
                    } else if (s < 8.5) {
                        scoreDistribution.put("7.0 - < 8.5", scoreDistribution.get("7.0 - < 8.5") + 1);
                    } else {
                        scoreDistribution.put("8.5 - 10.0", scoreDistribution.get("8.5 - 10.0") + 1);
                    }
                }
            }
        }

        totalAttempts = scores.size();
        if (totalAttempts > 0) {
            double sum = 0.0;
            double max = Double.NEGATIVE_INFINITY;
            double min = Double.POSITIVE_INFINITY;
            for (double s : scores) {
                sum += s;
                if (s > max) max = s;
                if (s < min) min = s;
            }
            avgScore = Math.round((sum / totalAttempts) * 100.0) / 100.0;
            highestScore = max;
            lowestScore = min;
        }

        // Question accuracy percentages
        Map<String, Double> questionAccuracy = new LinkedHashMap<>();
        String accuracySql = "SELECT eq.question_id, " +
                             "COUNT(aa.id) AS total_answered, " +
                             "SUM(CASE WHEN aa.is_correct = TRUE THEN 1 ELSE 0 END) AS correct_answers " +
                             "FROM exam_questions eq " +
                             "LEFT JOIN attempts a ON a.exam_id = eq.exam_id AND a.status IN ('SUBMITTED', 'EXPIRED') " +
                             "LEFT JOIN attempt_answers aa ON aa.attempt_id = a.id AND aa.question_id = eq.question_id " +
                             "WHERE eq.exam_id = ? " +
                             "GROUP BY eq.question_id, eq.question_order " +
                             "ORDER BY eq.question_order ASC";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(accuracySql)) {
            ps.setString(1, examId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String qId = rs.getString("question_id");
                    int correctAnswers = rs.getInt("correct_answers");
                    double accuracy = totalAttempts > 0 ?
                            Math.round((correctAnswers * 100.0 / totalAttempts) * 10.0) / 10.0 : 0.0;
                    questionAccuracy.put(qId, accuracy);
                }
            }
        }

        return new ExamStatsDto(examId, avgScore, highestScore, lowestScore, totalAttempts, scoreDistribution, questionAccuracy);
    }

    // =========================================================================
    // RETAKE REQUESTS WORKFLOW & ATTEMPT RESET
    // =========================================================================

    /**
     * Sinh viên gửi yêu cầu xin thi lại đối với một ca thi đã làm hoặc đã đóng.
     */
    public RetakeRequestDto submitRetakeRequest(String examId, String studentId, String reason, SyncLogWriter syncLogWriter)
            throws SQLException, ExamServerException {
        if (examId == null || examId.trim().isEmpty()) {
            throw new ExamServerException(ErrorCode.BAD_REQUEST, "Mã ca thi không được để trống");
        }

        // 1. Lấy thông tin sinh viên
        String studentCode = "";
        String studentName = "";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT username, full_name FROM users WHERE id = ?")) {
            ps.setString(1, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    studentCode = rs.getString("username");
                    studentName = rs.getString("full_name");
                } else {
                    throw new ExamServerException(ErrorCode.BAD_REQUEST, "Không tìm thấy thông tin thí sinh");
                }
            }
        }

        // 2. Lấy thông tin ca thi và kiểm tra phân công
        String examTitle = "";
        String subject = "";
        try (Connection conn = dbManager.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT e.title, e.subject FROM exams e " +
                    "JOIN exam_assignments ea ON e.id = ea.exam_id " +
                    "WHERE e.id = ? AND ea.student_id = ?")) {
                ps.setString(1, examId);
                ps.setString(2, studentId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        examTitle = rs.getString("title");
                        subject = rs.getString("subject");
                    } else {
                        throw new ExamServerException(ErrorCode.BAD_REQUEST, "Bạn không được phân công vào ca thi này");
                    }
                }
            }

            // 3. Kiểm tra xem đã có yêu cầu PENDING chưa
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id FROM retake_requests WHERE exam_id = ? AND student_id = ? AND status = 'PENDING'")) {
                ps.setString(1, examId);
                ps.setString(2, studentId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        throw new ExamServerException(ErrorCode.BAD_REQUEST, "Bạn đã gửi yêu cầu xin thi lại cho ca thi này và đang chờ duyệt");
                    }
                }
            }
        }

        // 4. Lưu yêu cầu mới vào CSDL và ghi sync log
        String requestId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String insertSql = "INSERT INTO retake_requests (id, exam_id, student_id, student_code, student_name, reason, status, created_at, updated_at) " +
                                   "VALUES (?, ?, ?, ?, ?, ?, 'PENDING', NOW(), NOW())";
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    ps.setString(1, requestId);
                    ps.setString(2, examId);
                    ps.setString(3, studentId);
                    ps.setString(4, studentCode);
                    ps.setString(5, studentName);
                    ps.setString(6, reason != null ? reason.trim() : "Xin thi lại do sự cố");
                    ps.executeUpdate();
                }

                if (syncLogWriter != null) {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("id", requestId);
                    payload.put("examId", examId);
                    payload.put("examTitle", examTitle);
                    payload.put("subject", subject);
                    payload.put("studentId", studentId);
                    payload.put("studentCode", studentCode);
                    payload.put("studentName", studentName);
                    payload.put("reason", reason);
                    payload.put("status", "PENDING");
                    payload.put("createdAt", now);
                    payload.put("updatedAt", now);
                    syncLogWriter.writeSyncLog(conn, "retake_requests", requestId, "RETAKE_CREATE", 1, payload);
                }

                conn.commit();
                log.info("Student {} submitted retake request for exam {}", studentCode, examId);
                return new RetakeRequestDto(requestId, examId, examTitle, subject, studentId, studentCode, studentName, reason, "PENDING", now, now);
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /**
     * Giảng viên lấy danh sách yêu cầu xin thi lại.
     */
    public List<RetakeRequestDto> listRetakeRequests(String examId, String statusFilter) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT r.id, r.exam_id, e.title AS exam_title, e.subject, " +
                "r.student_id, r.student_code, r.student_name, r.reason, r.status, " +
                "r.created_at, r.updated_at " +
                "FROM retake_requests r " +
                "JOIN exams e ON r.exam_id = e.id " +
                "WHERE 1=1 ");

        List<Object> params = new ArrayList<>();
        if (examId != null && !examId.trim().isEmpty() && !"ALL".equalsIgnoreCase(examId)) {
            sql.append("AND r.exam_id = ? ");
            params.add(examId.trim());
        }
        if (statusFilter != null && !statusFilter.trim().isEmpty() && !"ALL".equalsIgnoreCase(statusFilter)) {
            sql.append("AND r.status = ? ");
            params.add(statusFilter.trim());
        }

        sql.append("ORDER BY r.created_at DESC");

        List<RetakeRequestDto> list = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    String eId = rs.getString("exam_id");
                    String eTitle = rs.getString("exam_title");
                    String subj = rs.getString("subject");
                    String sId = rs.getString("student_id");
                    String sCode = rs.getString("student_code");
                    String sName = rs.getString("student_name");
                    String reason = rs.getString("reason");
                    String st = rs.getString("status");
                    Timestamp cAt = rs.getTimestamp("created_at");
                    Timestamp uAt = rs.getTimestamp("updated_at");

                    list.add(new RetakeRequestDto(id, eId, eTitle, subj, sId, sCode, sName, reason, st,
                            cAt != null ? cAt.getTime() : null,
                            uAt != null ? uAt.getTime() : null));
                }
            }
        }
        return list;
    }

    /**
     * Giảng viên phê duyệt hoặc từ chối yêu cầu thi lại.
     * Khi duyệt (APPROVE):
     * 1. Cập nhật trạng thái yêu cầu sang APPROVED.
     * 2. Xóa lượt thi cũ trong attempts (kèm cascade answers).
     * 3. Mở rộng giờ đóng ca thi nếu ca thi đã hết hạn để thí sinh có thể làm bài.
     * 4. Ghi sync log để đồng bộ sang cả 4 server.
     */
    public void decideRetakeRequest(String requestId, String action, String decidedBy, SyncLogWriter syncLogWriter)
            throws SQLException, ExamServerException {
        if (requestId == null || requestId.trim().isEmpty()) {
            throw new ExamServerException(ErrorCode.BAD_REQUEST, "Mã yêu cầu không hợp lệ");
        }
        if (!"APPROVE".equalsIgnoreCase(action) && !"REJECT".equalsIgnoreCase(action)) {
            throw new ExamServerException(ErrorCode.BAD_REQUEST, "Hành động chỉ có thể là APPROVE hoặc REJECT");
        }

        String examId = null;
        String studentId = null;
        String currentStatus = null;

        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT exam_id, student_id, status FROM retake_requests WHERE id = ?")) {
            ps.setString(1, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    examId = rs.getString("exam_id");
                    studentId = rs.getString("student_id");
                    currentStatus = rs.getString("status");
                } else {
                    throw new ExamServerException(ErrorCode.BAD_REQUEST, "Không tìm thấy yêu cầu xin thi lại");
                }
            }
        }

        if (!"PENDING".equalsIgnoreCase(currentStatus)) {
            throw new ExamServerException(ErrorCode.BAD_REQUEST, "Yêu cầu này đã được xử lý trước đó (Trạng thái: " + currentStatus + ")");
        }

        String targetStatus = "APPROVE".equalsIgnoreCase(action) ? "APPROVED" : "REJECTED";

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 1. Cập nhật trạng thái yêu cầu
                String updateReqSql = "UPDATE retake_requests SET status = ?, updated_at = NOW() WHERE id = ?";
                try (PreparedStatement ps = conn.prepareStatement(updateReqSql)) {
                    ps.setString(1, targetStatus);
                    ps.setString(2, requestId);
                    ps.executeUpdate();
                }

                if ("APPROVE".equalsIgnoreCase(action)) {
                    // 2. Tìm và xóa attempt cũ của sinh viên trong ca thi
                    String findAttemptSql = "SELECT id FROM attempts WHERE exam_id = ? AND student_id = ?";
                    List<String> attemptIds = new ArrayList<>();
                    try (PreparedStatement ps = conn.prepareStatement(findAttemptSql)) {
                        ps.setString(1, examId);
                        ps.setString(2, studentId);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                attemptIds.add(rs.getString("id"));
                            }
                        }
                    }

                    for (String attId : attemptIds) {
                        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM attempt_answers WHERE attempt_id = ?")) {
                            ps.setString(1, attId);
                            ps.executeUpdate();
                        }
                        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM attempts WHERE id = ?")) {
                            ps.setString(1, attId);
                            ps.executeUpdate();
                        }
                        if (syncLogWriter != null) {
                            Map<String, Object> attPayload = new HashMap<>();
                            attPayload.put("attemptId", attId);
                            attPayload.put("examId", examId);
                            attPayload.put("studentId", studentId);
                            syncLogWriter.writeSyncLog(conn, "attempts", attId, "DELETE", 1, attPayload);
                        }
                    }

                    // 3. Kiểm tra xem ca thi có đang bị đóng/hết hạn không. Nếu có, gia hạn để SV thi lại
                    String checkExamSql = "SELECT duration_minutes, end_time, status FROM exams WHERE id = ?";
                    int durationMinutes = 45;
                    Timestamp endTime = null;
                    try (PreparedStatement ps = conn.prepareStatement(checkExamSql)) {
                        ps.setString(1, examId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                durationMinutes = rs.getInt("duration_minutes");
                                endTime = rs.getTimestamp("end_time");
                            }
                        }
                    }

                    long now = System.currentTimeMillis();
                    if (endTime == null || endTime.getTime() <= now) {
                        // Gia hạn thêm thời gian: now + durationMinutes + 30 phút
                        long newEndTime = now + (durationMinutes + 30L) * 60000L;
                        String extendSql = "UPDATE exams SET end_time = ?, status = 'OPEN', version = version + 1 WHERE id = ?";
                        try (PreparedStatement ps = conn.prepareStatement(extendSql)) {
                            ps.setTimestamp(1, new Timestamp(newEndTime));
                            ps.setString(2, examId);
                            ps.executeUpdate();
                        }

                        if (syncLogWriter != null) {
                            Map<String, Object> examPayload = new HashMap<>();
                            examPayload.put("id", examId);
                            examPayload.put("endTime", newEndTime);
                            examPayload.put("status", "OPEN");
                            syncLogWriter.writeSyncLog(conn, "exams", examId, "UPDATE", 2, examPayload);
                        }
                        log.info("Extended exam {} endTime to {} for retake approval", examId, new Timestamp(newEndTime));
                    }
                }

                // Ghi sync log cho bảng retake_requests
                if (syncLogWriter != null) {
                    Map<String, Object> reqPayload = new HashMap<>();
                    reqPayload.put("id", requestId);
                    reqPayload.put("status", targetStatus);
                    reqPayload.put("examId", examId);
                    reqPayload.put("studentId", studentId);
                    reqPayload.put("updatedAt", System.currentTimeMillis());
                    syncLogWriter.writeSyncLog(conn, "retake_requests", requestId, "RETAKE_UPDATE", 2, reqPayload);
                }

                conn.commit();
                log.info("Decided retake request {} -> {} by lecturer {}", requestId, targetStatus, decidedBy);
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /**
     * Cho phép Giảng viên chủ động xóa/reset bài làm của một sinh viên để sinh viên được thi lại.
     */
    public void resetStudentAttempt(String examId, String studentCodeOrId, SyncLogWriter syncLogWriter)
            throws SQLException, ExamServerException {
        if (examId == null || examId.trim().isEmpty()) {
            throw new ExamServerException(ErrorCode.BAD_REQUEST, "Mã ca thi không được để trống");
        }
        if (studentCodeOrId == null || studentCodeOrId.trim().isEmpty()) {
            throw new ExamServerException(ErrorCode.BAD_REQUEST, "Mã sinh viên không được để trống");
        }

        String studentId = null;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT id FROM users WHERE id = ? OR username = ?")) {
            ps.setString(1, studentCodeOrId);
            ps.setString(2, studentCodeOrId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    studentId = rs.getString("id");
                } else {
                    throw new ExamServerException(ErrorCode.BAD_REQUEST, "Không tìm thấy sinh viên: " + studentCodeOrId);
                }
            }
        }

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String findAttemptSql = "SELECT id FROM attempts WHERE exam_id = ? AND student_id = ?";
                List<String> attemptIds = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement(findAttemptSql)) {
                    ps.setString(1, examId);
                    ps.setString(2, studentId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            attemptIds.add(rs.getString("id"));
                        }
                    }
                }

                for (String attId : attemptIds) {
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM attempt_answers WHERE attempt_id = ?")) {
                        ps.setString(1, attId);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM attempts WHERE id = ?")) {
                        ps.setString(1, attId);
                        ps.executeUpdate();
                    }
                    if (syncLogWriter != null) {
                        Map<String, Object> attPayload = new HashMap<>();
                        attPayload.put("attemptId", attId);
                        attPayload.put("examId", examId);
                        attPayload.put("studentId", studentId);
                        syncLogWriter.writeSyncLog(conn, "attempts", attId, "DELETE", 1, attPayload);
                    }
                }

                conn.commit();
                log.info("Reset attempt for student {} in exam {}", studentCodeOrId, examId);
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }
}
