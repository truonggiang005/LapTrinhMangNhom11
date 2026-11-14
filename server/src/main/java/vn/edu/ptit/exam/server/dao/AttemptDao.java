package vn.edu.ptit.exam.server.dao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.common.dto.AnswerItemDto;
import vn.edu.ptit.exam.common.model.Attempt;
import vn.edu.ptit.exam.server.db.DatabaseManager;

import java.sql.*;
import java.util.*;

public class AttemptDao {

    private static final Logger log = LoggerFactory.getLogger(AttemptDao.class);
    private final DatabaseManager dbManager;
    private final SyncLogDao syncLogDao;

    public AttemptDao(DatabaseManager dbManager, SyncLogDao syncLogDao) {
        this.dbManager = dbManager;
        this.syncLogDao = syncLogDao;
    }

    public boolean isStudentAssignedToExam(String studentId, String examId) throws SQLException {
        String sql = "SELECT 1 FROM exam_assignments WHERE student_id = ? AND exam_id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, studentId);
            ps.setString(2, examId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public Map<String, Object> getExamInfo(String examId) throws SQLException {
        String sql = "SELECT id, title, subject, duration_minutes, start_time, end_time, total_questions, " +
                     "max_score, show_score_type, status FROM exams WHERE id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, examId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", rs.getString("id"));
                    map.put("title", rs.getString("title"));
                    map.put("subject", rs.getString("subject"));
                    map.put("durationMinutes", rs.getInt("duration_minutes"));
                    map.put("startTime", rs.getTimestamp("start_time").getTime());
                    map.put("endTime", rs.getTimestamp("end_time").getTime());
                    map.put("totalQuestions", rs.getInt("total_questions"));
                    map.put("maxScore", rs.getDouble("max_score"));
                    map.put("showScoreType", rs.getString("show_score_type"));
                    map.put("status", rs.getString("status"));
                    return map;
                }
            }
        }
        return null;
    }

    public Attempt findAttemptByStudentAndExam(String studentId, String examId) throws SQLException {
        String sql = "SELECT id, exam_id, student_id, seed, start_time, deadline, submit_time, is_late, " +
                     "server_id, status, score, correct_count, total_questions, version FROM attempts " +
                     "WHERE student_id = ? AND exam_id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, studentId);
            ps.setString(2, examId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapAttempt(rs);
                }
            }
        }
        return null;
    }

    public Attempt findAttemptById(String attemptId) throws SQLException {
        String sql = "SELECT id, exam_id, student_id, seed, start_time, deadline, submit_time, is_late, " +
                     "server_id, status, score, correct_count, total_questions, version FROM attempts WHERE id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, attemptId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapAttempt(rs);
                }
            }
        }
        return null;
    }

    /**
     * Creates an attempt and writes ATTEMPT_CREATE sync_log in the SAME transaction.
     */
    public void createAttempt(Attempt attempt) throws SQLException {
        String insertSql = "INSERT INTO attempts (id, exam_id, student_id, seed, start_time, deadline, " +
                           "server_id, status, total_questions, version, created_at) " +
                           "VALUES (?, ?, ?, ?, ?, ?, ?, 'IN_PROGRESS', ?, 1, NOW())";

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    ps.setString(1, attempt.getId());
                    ps.setString(2, attempt.getExamId());
                    ps.setString(3, attempt.getStudentId());
                    ps.setLong(4, attempt.getSeed());
                    ps.setTimestamp(5, attempt.getStartTime());
                    ps.setTimestamp(6, attempt.getDeadline());
                    ps.setString(7, attempt.getServerId());
                    ps.setInt(8, attempt.getTotalQuestions());
                    ps.executeUpdate();
                }

                // Invariant 3: Write sync_log in the same transaction
                Map<String, Object> payload = new HashMap<>();
                payload.put("attemptId", attempt.getId());
                payload.put("examId", attempt.getExamId());
                payload.put("studentId", attempt.getStudentId());
                payload.put("seed", attempt.getSeed());
                payload.put("startTime", attempt.getStartTime().getTime());
                payload.put("deadline", attempt.getDeadline().getTime());
                payload.put("serverId", attempt.getServerId());
                payload.put("totalQuestions", attempt.getTotalQuestions());

                syncLogDao.writeSyncLog(conn, "attempts", attempt.getId(), "ATTEMPT_CREATE", 1, payload);

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /**
     * Loads previously saved answers for an attempt.
     */
    public Map<String, AnswerItemDto> loadSavedAnswers(String attemptId) throws SQLException {
        String sql = "SELECT question_id, selected_option_id, is_marked FROM attempt_answers WHERE attempt_id = ?";
        Map<String, AnswerItemDto> map = new HashMap<>();
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, attemptId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String qId = rs.getString("question_id");
                    String optId = rs.getString("selected_option_id");
                    boolean marked = rs.getBoolean("is_marked");
                    map.put(qId, new AnswerItemDto(qId, optId, marked));
                }
            }
        }
        return map;
    }

    /**
     * Saves answer updates and writes ANSWER_UPSERT sync_log in the same transaction.
     */
    public int saveAnswersBatch(String attemptId, long seq, List<AnswerItemDto> answers) throws SQLException {
        if (answers == null || answers.isEmpty()) {
            return 0;
        }

        String upsertSql = "INSERT INTO attempt_answers (id, attempt_id, question_id, selected_option_id, is_marked, seq, version, updated_at) " +
                           "VALUES (?, ?, ?, ?, ?, ?, 1, NOW()) " +
                           "ON DUPLICATE KEY UPDATE selected_option_id = VALUES(selected_option_id), " +
                           "is_marked = VALUES(is_marked), seq = VALUES(seq), version = version + 1, updated_at = NOW()";

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(upsertSql)) {
                    for (AnswerItemDto ans : answers) {
                        ps.setString(1, UUID.randomUUID().toString());
                        ps.setString(2, attemptId);
                        ps.setString(3, ans.getQuestionId());
                        ps.setString(4, ans.getSelectedOptionId());
                        ps.setBoolean(5, ans.isMarked());
                        ps.setLong(6, seq);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }

                // Invariant 3: Write sync_log in same transaction
                Map<String, Object> payload = new HashMap<>();
                payload.put("attemptId", attemptId);
                payload.put("seq", seq);
                payload.put("answers", answers);

                syncLogDao.writeSyncLog(conn, "attempt_answers", attemptId, "ANSWER_UPSERT", 1, payload);

                conn.commit();
                return answers.size();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /**
     * Invariant 4: Idempotent submit.
     * UPDATE ... WHERE id = ? AND status = 'IN_PROGRESS'.
     * If 0 rows updated, returns null so caller re-reads the already submitted result.
     * Returns generated opId if successfully submitted.
     */
    public String submitAttemptAndGetOpId(String attemptId, double score, int correctCount, boolean isLate) throws SQLException {
        if (dbManager == null) {
            boolean ok = submitAttemptIdempotent(attemptId, score, correctCount, isLate);
            return ok ? "op-test-" + attemptId : null;
        }

        String updateSql = "UPDATE attempts SET status = 'SUBMITTED', submit_time = NOW(), " +
                           "score = ?, correct_count = ?, is_late = ?, version = version + 1 " +
                           "WHERE id = ? AND status = 'IN_PROGRESS'";

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                int updatedRows;
                try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                    ps.setDouble(1, score);
                    ps.setInt(2, correctCount);
                    ps.setBoolean(3, isLate);
                    ps.setString(4, attemptId);
                    updatedRows = ps.executeUpdate();
                }

                if (updatedRows == 0) {
                    conn.rollback();
                    return null; // Already submitted by another process!
                }

                // Write sync_log in same transaction
                Map<String, Object> payload = new HashMap<>();
                payload.put("attemptId", attemptId);
                payload.put("score", score);
                payload.put("correctCount", correctCount);
                payload.put("isLate", isLate);
                payload.put("status", "SUBMITTED");

                String opId = syncLogDao.writeSyncLog(conn, "attempts", attemptId, "ATTEMPT_SUBMIT", 2, payload);

                conn.commit();
                return opId;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    public boolean submitAttemptIdempotent(String attemptId, double score, int correctCount, boolean isLate) throws SQLException {
        return submitAttemptAndGetOpId(attemptId, score, correctCount, isLate) != null;
    }

    /**
     * Scans for attempts that are still IN_PROGRESS but have exceeded deadline + tolerance.
     */
    public List<Attempt> scanExpiredAttempts(long lateToleranceMs) throws SQLException {
        if (dbManager == null) {
            return Collections.emptyList();
        }
        Timestamp threshold = new Timestamp(System.currentTimeMillis() - lateToleranceMs);
        String sql = "SELECT id, exam_id, student_id, seed, start_time, deadline, submit_time, is_late, " +
                     "server_id, status, score, correct_count, total_questions, version FROM attempts " +
                     "WHERE status = 'IN_PROGRESS' AND deadline < ?";

        List<Attempt> list = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, threshold);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapAttempt(rs));
                }
            }
        }
        return list;
    }

    /**
     * Luồng 8 Bước 5: ATTEMPT_REASSIGN
     * Reassigns an attempt to a new server during failover.
     * Updates server_id and version in attempts, and writes sync_log in the same transaction.
     */
    public boolean reassignAttempt(String attemptId, String newServerId) throws SQLException {
        if (dbManager == null) {
            return true;
        }

        String sql = "UPDATE attempts SET server_id = ?, version = version + 1 WHERE id = ?";
        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                int updatedRows;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, newServerId);
                    ps.setString(2, attemptId);
                    updatedRows = ps.executeUpdate();
                }

                if (updatedRows == 0) {
                    conn.rollback();
                    return false;
                }

                if (syncLogDao != null) {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("attemptId", attemptId);
                    payload.put("serverId", newServerId);

                    syncLogDao.writeSyncLog(conn, "attempts", attemptId, "ATTEMPT_REASSIGN", 2, payload);
                }

                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /**
     * Luồng 8 Bước 9: Scans for orphaned attempts belonging to DOWN servers that have expired.
     */
    public List<Attempt> scanExpiredAttemptsForDownServers(List<String> downServerIds, long lateToleranceMs) throws SQLException {
        if (dbManager == null || downServerIds == null || downServerIds.isEmpty()) {
            return Collections.emptyList();
        }

        Timestamp threshold = new Timestamp(System.currentTimeMillis() - lateToleranceMs);
        String placeholders = String.join(",", Collections.nCopies(downServerIds.size(), "?"));
        String sql = "SELECT id, exam_id, student_id, seed, start_time, deadline, submit_time, is_late, " +
                     "server_id, status, score, correct_count, total_questions, version FROM attempts " +
                     "WHERE status = 'IN_PROGRESS' AND server_id IN (" + placeholders + ") " +
                     "AND deadline < ?";

        List<Attempt> list = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            for (String sid : downServerIds) {
                ps.setString(idx++, sid);
            }
            ps.setTimestamp(idx, threshold);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapAttempt(rs));
                }
            }
        }
        return list;
    }

    /**
     * Lists historical submitted attempts for a student with pagination.
     */
    public List<Map<String, Object>> listHistoryForStudent(String studentId, int page, int pageSize) throws SQLException {
        String sql = "SELECT a.id, a.exam_id, e.title, e.subject, a.score, a.correct_count, a.total_questions, " +
                     "a.submit_time, a.is_late, e.show_score_type, e.end_time " +
                     "FROM attempts a " +
                     "JOIN exams e ON a.exam_id = e.id " +
                     "WHERE a.student_id = ? AND a.status = 'SUBMITTED' " +
                     "ORDER BY a.submit_time DESC LIMIT ? OFFSET ?";

        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, pageSize);
        int offset = (safePage - 1) * safePageSize;

        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, studentId);
            ps.setInt(2, safePageSize);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> map = new HashMap<>();
                    map.put("attemptId", rs.getString("id"));
                    map.put("examId", rs.getString("exam_id"));
                    map.put("title", rs.getString("title"));
                    map.put("subject", rs.getString("subject"));
                    map.put("score", rs.getObject("score"));
                    map.put("correctCount", rs.getInt("correct_count"));
                    map.put("totalQuestions", rs.getInt("total_questions"));
                    map.put("submitTime", rs.getTimestamp("submit_time") != null ? rs.getTimestamp("submit_time").getTime() : null);
                    map.put("isLate", rs.getBoolean("is_late"));
                    list.add(map);
                }
            }
        }
        return list;
    }

    private Attempt mapAttempt(ResultSet rs) throws SQLException {
        Attempt a = new Attempt();
        a.setId(rs.getString("id"));
        a.setExamId(rs.getString("exam_id"));
        a.setStudentId(rs.getString("student_id"));
        a.setSeed(rs.getLong("seed"));
        a.setStartTime(rs.getTimestamp("start_time"));
        a.setDeadline(rs.getTimestamp("deadline"));
        a.setSubmitTime(rs.getTimestamp("submit_time"));
        a.setLate(rs.getBoolean("is_late"));
        a.setServerId(rs.getString("server_id"));
        a.setStatus(rs.getString("status"));
        a.setScore((Double) rs.getObject("score"));
        a.setCorrectCount(rs.getInt("correct_count"));
        a.setTotalQuestions(rs.getInt("total_questions"));
        a.setVersion(rs.getInt("version"));
        return a;
    }
}
