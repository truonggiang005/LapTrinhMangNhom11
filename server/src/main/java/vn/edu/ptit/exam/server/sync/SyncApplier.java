package vn.edu.ptit.exam.server.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.common.dto.AnswerItemDto;
import vn.edu.ptit.exam.common.util.JsonUtil;
import vn.edu.ptit.exam.server.db.DatabaseManager;
import vn.edu.ptit.exam.server.sync.model.SyncLogEntry;

import java.sql.*;
import java.util.*;

/**
 * Applies sync operations received from peer servers into the local database.
 * Strictly enforces Invariant 10 (Conflict Resolution) and Invariant 9 (No cascading sync logs).
 */
public class SyncApplier {

    private static final Logger log = LoggerFactory.getLogger(SyncApplier.class);

    private final DatabaseManager dbManager;
    private final SyncPeerStateDao syncPeerStateDao;
    private final String localServerId;

    public SyncApplier(DatabaseManager dbManager, SyncPeerStateDao syncPeerStateDao, String localServerId) {
        this.dbManager = dbManager;
        this.syncPeerStateDao = syncPeerStateDao;
        this.localServerId = localServerId;
    }

    /**
     * Applies a single sync operation in a transaction.
     * Skips if (origin, seq) has already been applied.
     *
     * @return true if successfully processed (either applied or safely skipped)
     */
    public boolean applyOperation(SyncLogEntry entry) {
        if (entry == null) return true;

        String origin = entry.getOrigin();
        long seq = entry.getSeq();
        String opId = entry.getOpId();

        // 1. Check idempotency: skip if already applied
        if (syncPeerStateDao.isOperationApplied(origin, seq)) {
            log.debug("Skipping already applied operation opId={} (origin={}, seq={})", opId, origin, seq);
            return true;
        }

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 2. Dispatch based on table name and operation type with Invariant 10 conflict resolution
                String table = entry.getTableName();
                String opType = entry.getOpType();

                if ("EXAM_PACKAGE".equalsIgnoreCase(opType) || "exams".equalsIgnoreCase(table)) {
                    applyExamOperation(conn, entry);
                } else if ("questions".equalsIgnoreCase(table)) {
                    applyQuestionOperation(conn, entry);
                } else if ("users".equalsIgnoreCase(table)) {
                    applyUserOperation(conn, entry);
                } else if ("attempts".equalsIgnoreCase(table)) {
                    applyAttemptOperation(conn, entry);
                } else if ("attempt_answers".equalsIgnoreCase(table)) {
                    applyAnswersOperation(conn, entry);
                } else if ("audit_log".equalsIgnoreCase(table)) {
                    applyAuditLogOperation(conn, entry);
                } else if ("retake_requests".equalsIgnoreCase(table)) {
                    applyRetakeOperation(conn, entry);
                } else {
                    log.warn("Unknown table {} for opId={}, treating as generic apply", table, opId);
                }

                // 3. Mark in sync_applied within the SAME transaction
                syncPeerStateDao.recordOperationApplied(conn, origin, seq, opId);

                conn.commit();

                // 4. Update sync_peer_state pointer
                syncPeerStateDao.updateLastReceivedSeq(origin, seq);

                log.debug("Successfully applied sync operation opId={}", opId);
                return true;
            } catch (Exception e) {
                conn.rollback();
                log.error("Failed to apply sync operation opId={}: {}", opId, e.getMessage(), e);
                return false;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.error("Database connection error while applying opId={}: {}", opId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Applies a batch of sync operations in sequential order.
     * Returns the highest continuously applied sequence number.
     */
    public long applyBatch(String origin, List<SyncLogEntry> entries) {
        if (entries == null || entries.isEmpty()) return 0L;

        long highestContinuousSeq = 0L;
        for (SyncLogEntry entry : entries) {
            boolean success = applyOperation(entry);
            if (success) {
                if (entry.getSeq() > highestContinuousSeq) {
                    highestContinuousSeq = entry.getSeq();
                }
            } else {
                log.warn("Failed to apply entry {} in batch, stopping batch application", entry.getOpId());
                break;
            }
        }
        return highestContinuousSeq;
    }

    // =========================================================================
    // INVARIANT 10: CONFLICT RESOLUTION RULES
    // =========================================================================

    /**
     * Rule: Catalogs (questions) -> Higher version wins.
     * If versions are equal, origin server with lower ID wins.
     */
    private void applyQuestionOperation(Connection conn, SyncLogEntry entry) throws SQLException {
        String qId = entry.getRecordId();
        int incomingVersion = entry.getVersion();
        String origin = entry.getOrigin();

        Integer localVersion = null;
        String sqlCheck = "SELECT version FROM questions WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sqlCheck)) {
            ps.setString(1, qId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    localVersion = rs.getInt("version");
                }
            }
        }

        if (localVersion != null) {
            ConflictWinner winner = resolveCatalogConflict(incomingVersion, origin, localVersion, localServerId);
            if (winner == ConflictWinner.LOCAL_WINS) {
                log.debug("Question {} conflict: local version {} ({}) wins over incoming {} ({}), ignoring",
                        qId, localVersion, localServerId, incomingVersion, origin);
                return;
            }
        }

        JsonNode payload = parseJson(entry.getPayloadJson());
        if ("QUESTION_DELETE".equalsIgnoreCase(entry.getOpType())) {
            String sqlDelete = "UPDATE questions SET is_deleted = TRUE, version = ? WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sqlDelete)) {
                ps.setInt(1, incomingVersion);
                ps.setString(2, qId);
                ps.executeUpdate();
            }
        } else {
            // QUESTION_CREATE or QUESTION_UPDATE
            String subject = payload.path("subject").asText("");
            int chapter = payload.path("chapter").asInt(1);
            String difficulty = payload.path("difficulty").asText("MEDIUM");
            String content = payload.path("content").asText("");
            boolean deleted = payload.path("deleted").asBoolean(false);

            String upsertSql = "INSERT INTO questions (id, subject, chapter, difficulty, content, is_deleted, version) " +
                               "VALUES (?, ?, ?, ?, ?, ?, ?) " +
                               "ON DUPLICATE KEY UPDATE subject = VALUES(subject), chapter = VALUES(chapter), " +
                               "difficulty = VALUES(difficulty), content = VALUES(content), is_deleted = VALUES(is_deleted), version = VALUES(version)";
            try (PreparedStatement ps = conn.prepareStatement(upsertSql)) {
                ps.setString(1, qId);
                ps.setString(2, subject);
                ps.setInt(3, chapter);
                ps.setString(4, difficulty);
                ps.setString(5, content);
                ps.setBoolean(6, deleted);
                ps.setInt(7, incomingVersion);
                ps.executeUpdate();
            }

            // Options if provided in payload
            if (payload.has("options") && payload.get("options").isArray()) {
                String deleteOldOptions = "DELETE FROM question_options WHERE question_id = ?";
                try (PreparedStatement ps = conn.prepareStatement(deleteOldOptions)) {
                    ps.setString(1, qId);
                    ps.executeUpdate();
                }

                String insertOption = "INSERT INTO question_options (id, question_id, option_key, content, is_correct, order_num) " +
                                      "VALUES (?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(insertOption)) {
                    for (JsonNode optNode : payload.get("options")) {
                        ps.setString(1, optNode.path("id").asText(UUID.randomUUID().toString()));
                        ps.setString(2, qId);
                        ps.setString(3, optNode.path("optionKey").asText("A"));
                        ps.setString(4, optNode.path("content").asText(""));
                        ps.setBoolean(5, optNode.path("isCorrect").asBoolean(false));
                        ps.setInt(6, optNode.path("orderNum").asInt(1));
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
        }
    }

    /**
     * Rule: Catalogs (exams / EXAM_PACKAGE) -> Higher version wins; if equal, lower origin wins.
     * Bundles exams, exam_questions, exam_assignments atomically.
     */
    private void applyExamOperation(Connection conn, SyncLogEntry entry) throws SQLException {
        JsonNode payload = parseJson(entry.getPayloadJson());
        String examId = entry.getRecordId();
        int incomingVersion = entry.getVersion();
        String origin = entry.getOrigin();

        Integer localVersion = null;
        String sqlCheck = "SELECT version FROM exams WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sqlCheck)) {
            ps.setString(1, examId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    localVersion = rs.getInt("version");
                }
            }
        }

        if (localVersion != null) {
            ConflictWinner winner = resolveCatalogConflict(incomingVersion, origin, localVersion, localServerId);
            if (winner == ConflictWinner.LOCAL_WINS) {
                log.debug("Exam {} conflict: local version {} ({}) wins over incoming {} ({}), ignoring",
                        examId, localVersion, localServerId, incomingVersion, origin);
                return;
            }
        }

        JsonNode examNode = payload.has("exam") ? payload.get("exam") : payload;
        String title = examNode.path("title").asText("");
        String subject = examNode.path("subject").asText("");
        int durationMinutes = examNode.path("durationMinutes").asInt(45);
        long startTime = examNode.path("startTime").asLong(System.currentTimeMillis());
        long endTime = examNode.path("endTime").asLong(System.currentTimeMillis() + 3600000L);
        int totalQuestions = examNode.path("totalQuestions").asInt(40);
        double maxScore = examNode.path("maxScore").asDouble(10.0);
        String showScoreType = examNode.path("showScoreType").asText("IMMEDIATE");
        String status = examNode.path("status").asText("OPEN");

        String upsertExam = "INSERT INTO exams (id, title, subject, duration_minutes, start_time, end_time, " +
                            "total_questions, max_score, show_score_type, status, version) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                            "ON DUPLICATE KEY UPDATE title = VALUES(title), subject = VALUES(subject), " +
                            "duration_minutes = VALUES(duration_minutes), start_time = VALUES(start_time), " +
                            "end_time = VALUES(end_time), total_questions = VALUES(total_questions), " +
                            "max_score = VALUES(max_score), show_score_type = VALUES(show_score_type), " +
                            "status = VALUES(status), version = VALUES(version)";

        try (PreparedStatement ps = conn.prepareStatement(upsertExam)) {
            ps.setString(1, examId);
            ps.setString(2, title);
            ps.setString(3, subject);
            ps.setInt(4, durationMinutes);
            ps.setTimestamp(5, new Timestamp(startTime));
            ps.setTimestamp(6, new Timestamp(endTime));
            ps.setInt(7, totalQuestions);
            ps.setDouble(8, maxScore);
            ps.setString(9, showScoreType);
            ps.setString(10, status);
            ps.setInt(11, incomingVersion);
            ps.executeUpdate();
        }

        // Apply exam_questions if included in package
        if (payload.has("questions") && payload.get("questions").isArray()) {
            String insertEq = "INSERT IGNORE INTO exam_questions (id, exam_id, question_id, question_order) VALUES (?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insertEq)) {
                int order = 1;
                for (JsonNode qNode : payload.get("questions")) {
                    String eqId = qNode.path("id").asText(UUID.randomUUID().toString());
                    String qId = qNode.path("questionId").asText(qNode.asText());
                    ps.setString(1, eqId);
                    ps.setString(2, examId);
                    ps.setString(3, qId);
                    ps.setInt(4, qNode.path("order").asInt(order++));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }

        // Apply exam_assignments if included in package
        if (payload.has("assignments") && payload.get("assignments").isArray()) {
            String insertAss = "INSERT IGNORE INTO exam_assignments (id, exam_id, student_id, assigned_at) VALUES (?, ?, ?, NOW())";
            try (PreparedStatement ps = conn.prepareStatement(insertAss)) {
                for (JsonNode assNode : payload.get("assignments")) {
                    String assId = assNode.path("id").asText(UUID.randomUUID().toString());
                    String studentId = assNode.path("studentId").asText(assNode.asText());
                    ps.setString(1, assId);
                    ps.setString(2, examId);
                    ps.setString(3, studentId);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
    }

    /**
     * Rule: Catalogs (users) -> Higher version wins; if equal, lower origin wins.
     */
    private void applyUserOperation(Connection conn, SyncLogEntry entry) throws SQLException {
        String userId = entry.getRecordId();
        int incomingVersion = entry.getVersion();
        String origin = entry.getOrigin();

        Integer localVersion = null;
        String sqlCheck = "SELECT version FROM users WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sqlCheck)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    localVersion = rs.getInt("version");
                }
            }
        }

        if (localVersion != null) {
            ConflictWinner winner = resolveCatalogConflict(incomingVersion, origin, localVersion, localServerId);
            if (winner == ConflictWinner.LOCAL_WINS) {
                return;
            }
        }

        JsonNode payload = parseJson(entry.getPayloadJson());
        String username = payload.path("username").asText("");
        String passwordHash = payload.path("passwordHash").asText("");
        String fullName = payload.path("fullName").asText("");
        String email = payload.has("email") ? payload.path("email").asText(null) : null;
        String role = payload.path("role").asText("STUDENT");
        String className = payload.has("className") ? payload.path("className").asText(null) : null;
        String status = payload.path("status").asText(payload.path("isLocked").asBoolean(false) ? "LOCKED" : "ACTIVE");

        String upsertUser = "INSERT INTO users (id, username, password_hash, full_name, email, role, class_name, status, version) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                            "ON DUPLICATE KEY UPDATE full_name = VALUES(full_name), email = VALUES(email), " +
                            "role = VALUES(role), class_name = VALUES(class_name), status = VALUES(status), version = VALUES(version)";
        if (!passwordHash.isEmpty()) {
            upsertUser = "INSERT INTO users (id, username, password_hash, full_name, email, role, class_name, status, version) " +
                         "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                         "ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash), full_name = VALUES(full_name), " +
                         "email = VALUES(email), role = VALUES(role), class_name = VALUES(class_name), status = VALUES(status), version = VALUES(version)";
        }

        try (PreparedStatement ps = conn.prepareStatement(upsertUser)) {
            ps.setString(1, userId);
            ps.setString(2, username);
            ps.setString(3, passwordHash);
            ps.setString(4, fullName);
            ps.setString(5, email);
            ps.setString(6, role);
            ps.setString(7, className);
            ps.setString(8, status);
            ps.setInt(9, incomingVersion);
            ps.executeUpdate();
        }
    }

    /**
     * Rule: attempts
     * - SUBMITTED cannot be overwritten by IN_PROGRESS.
     * - If two SUBMITTED attempts: earlier submit_time wins.
     */
    private void applyAttemptOperation(Connection conn, SyncLogEntry entry) throws SQLException {
        String attemptId = entry.getRecordId();
        JsonNode payload = parseJson(entry.getPayloadJson());

        if ("DELETE".equalsIgnoreCase(entry.getOpType())) {
            String delAns = "DELETE FROM attempt_answers WHERE attempt_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(delAns)) {
                ps.setString(1, attemptId);
                ps.executeUpdate();
            }
            String delAtt = "DELETE FROM attempts WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(delAtt)) {
                ps.setString(1, attemptId);
                ps.executeUpdate();
            }
            log.info("Applied DELETE sync on attempt {}", attemptId);
            return;
        }

        String localStatus = null;
        Timestamp localSubmitTime = null;
        String checkSql = "SELECT status, submit_time FROM attempts WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
            ps.setString(1, attemptId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    localStatus = rs.getString("status");
                    localSubmitTime = rs.getTimestamp("submit_time");
                }
            }
        }

        String incomingStatus = payload.path("status").asText("IN_PROGRESS");
        Long incomingSubmitTimeVal = payload.has("submitTime") ? payload.path("submitTime").asLong() : null;
        Timestamp incomingSubmitTime = incomingSubmitTimeVal != null ? new Timestamp(incomingSubmitTimeVal) : null;
        Long localSubmitTimeVal = localSubmitTime != null ? localSubmitTime.getTime() : null;

        if (localStatus != null) {
            ConflictWinner winner = resolveAttemptConflict(incomingStatus, incomingSubmitTimeVal, localStatus, localSubmitTimeVal);
            if (winner == ConflictWinner.LOCAL_WINS) {
                log.info("Attempt {} conflict resolved: local (status={}, submitTime={}) wins over incoming (status={}, submitTime={}), ignoring.",
                        attemptId, localStatus, localSubmitTime, incomingStatus, incomingSubmitTime);
                return;
            }
        }

        if ("ATTEMPT_CREATE".equalsIgnoreCase(entry.getOpType()) && localStatus == null) {
            String insertAttempt = "INSERT INTO attempts (id, exam_id, student_id, seed, start_time, deadline, " +
                                   "server_id, status, total_questions, version, created_at) " +
                                   "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())";
            try (PreparedStatement ps = conn.prepareStatement(insertAttempt)) {
                ps.setString(1, attemptId);
                ps.setString(2, payload.path("examId").asText());
                ps.setString(3, payload.path("studentId").asText());
                ps.setLong(4, payload.path("seed").asLong());
                ps.setTimestamp(5, new Timestamp(payload.path("startTime").asLong()));
                ps.setTimestamp(6, new Timestamp(payload.path("deadline").asLong()));
                ps.setString(7, payload.path("serverId").asText());
                ps.setString(8, incomingStatus);
                ps.setInt(9, payload.path("totalQuestions").asInt(40));
                ps.setInt(10, entry.getVersion());
                ps.executeUpdate();
            }
        } else {
            // Update to SUBMITTED or reassign
            Double score = payload.has("score") ? payload.path("score").asDouble() : null;
            Integer correctCount = payload.has("correctCount") ? payload.path("correctCount").asInt() : null;
            boolean isLate = payload.path("isLate").asBoolean(false);

            String updateSql = "UPDATE attempts SET status = ?, submit_time = ?, score = ?, correct_count = ?, " +
                               "is_late = ?, version = GREATEST(version, ?) WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setString(1, incomingStatus);
                ps.setTimestamp(2, incomingSubmitTime != null ? incomingSubmitTime : new Timestamp(System.currentTimeMillis()));
                if (score != null) ps.setDouble(3, score); else ps.setNull(3, Types.DECIMAL);
                if (correctCount != null) ps.setInt(4, correctCount); else ps.setNull(4, Types.INTEGER);
                ps.setBoolean(5, isLate);
                ps.setInt(6, entry.getVersion());
                ps.setString(7, attemptId);
                ps.executeUpdate();
            }
        }
    }

    /**
     * Rule: attempt_answers
     * - updated_at later wins.
     * - BUT ignore answers updated after the submit_time of a SUBMITTED attempt!
     */
    private void applyAnswersOperation(Connection conn, SyncLogEntry entry) throws SQLException {
        JsonNode payload = parseJson(entry.getPayloadJson());
        String attemptId = payload.has("attemptId") ? payload.path("attemptId").asText() : entry.getRecordId();

        // 1. Check attempt's submit_time
        String attemptStatus = null;
        Timestamp submitTime = null;
        String checkAttemptSql = "SELECT status, submit_time FROM attempts WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(checkAttemptSql)) {
            ps.setString(1, attemptId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    attemptStatus = rs.getString("status");
                    submitTime = rs.getTimestamp("submit_time");
                }
            }
        }

        long submitTimeMs = (submitTime != null) ? submitTime.getTime() : Long.MAX_VALUE;
        boolean isAttemptSubmitted = "SUBMITTED".equalsIgnoreCase(attemptStatus);

        List<AnswerItemDto> answers = null;
        if (payload.has("answers")) {
            answers = JsonUtil.convertValue(payload.get("answers"), new TypeReference<List<AnswerItemDto>>() {});
        }

        if (answers == null || answers.isEmpty()) return;

        long incomingTime = entry.getCreatedAt();

        // Conflict Rule: Discard answers after submit_time if attempt is SUBMITTED
        if (shouldDiscardAnswer(isAttemptSubmitted, submitTimeMs, incomingTime)) {
            log.info("Discarding incoming answers for attempt {} because updated_at ({}) > submit_time ({})",
                    attemptId, incomingTime, submitTimeMs);
            return;
        }

        String upsertAnswer = "INSERT INTO attempt_answers (id, attempt_id, question_id, selected_option_id, is_marked, seq, version, updated_at) " +
                              "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                              "ON DUPLICATE KEY UPDATE " +
                              "selected_option_id = IF(VALUES(updated_at) >= updated_at, VALUES(selected_option_id), selected_option_id), " +
                              "is_marked = IF(VALUES(updated_at) >= updated_at, VALUES(is_marked), is_marked), " +
                              "seq = IF(VALUES(updated_at) >= updated_at, VALUES(seq), seq), " +
                              "version = version + 1, " +
                              "updated_at = IF(VALUES(updated_at) >= updated_at, VALUES(updated_at), updated_at)";

        try (PreparedStatement ps = conn.prepareStatement(upsertAnswer)) {
            for (AnswerItemDto ans : answers) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, attemptId);
                ps.setString(3, ans.getQuestionId());
                ps.setString(4, ans.getSelectedOptionId());
                ps.setBoolean(5, ans.isMarked());
                ps.setLong(6, entry.getSeq());
                ps.setInt(7, entry.getVersion());
                ps.setTimestamp(8, new Timestamp(incomingTime));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * Rule: audit_log -> Append-only, deduplicate by id.
     */
    private void applyAuditLogOperation(Connection conn, SyncLogEntry entry) throws SQLException {
        JsonNode payload = parseJson(entry.getPayloadJson());
        String logId = payload.has("id") ? payload.path("id").asText() : entry.getRecordId();

        String insertSql = "INSERT IGNORE INTO audit_log (id, user_id, action, entity_type, entity_id, server_id, ip_address, details, created_at) " +
                           "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
            ps.setString(1, logId);
            ps.setString(2, payload.path("userId").asText(null));
            ps.setString(3, payload.path("action").asText("SYSTEM"));
            ps.setString(4, payload.path("entityType").asText(null));
            ps.setString(5, payload.path("entityId").asText(null));
            ps.setString(6, payload.path("serverId").asText(entry.getOrigin()));
            ps.setString(7, payload.path("ipAddress").asText(null));
            ps.setString(8, payload.path("details").asText(null));
            ps.setTimestamp(9, new Timestamp(entry.getCreatedAt()));
            ps.executeUpdate();
        }
    }

    public enum ConflictWinner {
        INCOMING_WINS,
        LOCAL_WINS
    }

    /**
     * Invariant 10: Catalogs -> Higher version wins.
     * If versions are equal, origin server with lower ID wins.
     */
    public static ConflictWinner resolveCatalogConflict(int incomingVersion, String incomingOrigin,
                                                         int localVersion, String localOrigin) {
        if (incomingVersion > localVersion) return ConflictWinner.INCOMING_WINS;
        if (incomingVersion < localVersion) return ConflictWinner.LOCAL_WINS;
        // Equal version tie-breaker: lower origin server ID wins
        return compareOrigin(incomingOrigin, localOrigin) < 0 ? ConflictWinner.INCOMING_WINS : ConflictWinner.LOCAL_WINS;
    }

    /**
     * Invariant 10: attempts
     * - SUBMITTED cannot be overwritten by IN_PROGRESS.
     * - If both SUBMITTED, earlier submit_time wins.
     */
    public static ConflictWinner resolveAttemptConflict(String incomingStatus, Long incomingSubmitTime,
                                                        String localStatus, Long localSubmitTime) {
        if ("SUBMITTED".equalsIgnoreCase(localStatus) && "IN_PROGRESS".equalsIgnoreCase(incomingStatus)) {
            return ConflictWinner.LOCAL_WINS;
        }
        if ("IN_PROGRESS".equalsIgnoreCase(localStatus) && "SUBMITTED".equalsIgnoreCase(incomingStatus)) {
            return ConflictWinner.INCOMING_WINS;
        }
        if ("SUBMITTED".equalsIgnoreCase(localStatus) && "SUBMITTED".equalsIgnoreCase(incomingStatus)) {
            if (localSubmitTime != null && incomingSubmitTime != null) {
                if (incomingSubmitTime < localSubmitTime) {
                    return ConflictWinner.INCOMING_WINS;
                }
                return ConflictWinner.LOCAL_WINS;
            }
        }
        return ConflictWinner.INCOMING_WINS;
    }

    /**
     * Invariant 10: attempt_answers
     * - Discard answers updated after the submit_time of a SUBMITTED attempt.
     */
    public static boolean shouldDiscardAnswer(boolean isAttemptSubmitted, long attemptSubmitTime, long answerUpdatedAt) {
        return isAttemptSubmitted && answerUpdatedAt > attemptSubmitTime;
    }

    /**
     * Invariant 10: attempt_answers
     * - Later updated_at wins.
     */
    public static ConflictWinner resolveAnswerConflict(long incomingUpdatedAt, long localUpdatedAt) {
        return incomingUpdatedAt >= localUpdatedAt ? ConflictWinner.INCOMING_WINS : ConflictWinner.LOCAL_WINS;
    }

    public static int compareOrigin(String origin1, String origin2) {
        if (origin1 == null && origin2 == null) return 0;
        if (origin1 == null) return -1;
        if (origin2 == null) return 1;
        try {
            int i1 = Integer.parseInt(origin1.replaceAll("\\D+", ""));
            int i2 = Integer.parseInt(origin2.replaceAll("\\D+", ""));
            return Integer.compare(i1, i2);
        } catch (Exception e) {
            return origin1.compareTo(origin2);
        }
    }

    private void applyRetakeOperation(Connection conn, SyncLogEntry entry) throws SQLException {
        String requestId = entry.getRecordId();
        JsonNode payload = parseJson(entry.getPayloadJson());

        if ("DELETE".equalsIgnoreCase(entry.getOpType())) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM retake_requests WHERE id = ?")) {
                ps.setString(1, requestId);
                ps.executeUpdate();
            }
            return;
        }

        String examId = payload.path("examId").asText();
        String studentId = payload.path("studentId").asText();
        String studentCode = payload.path("studentCode").asText("");
        String studentName = payload.path("studentName").asText("");
        String reason = payload.path("reason").asText("");
        String status = payload.path("status").asText("PENDING");

        String upsertSql = "INSERT INTO retake_requests (id, exam_id, student_id, student_code, student_name, reason, status, created_at, updated_at) " +
                           "VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), NOW()) " +
                           "ON DUPLICATE KEY UPDATE status = VALUES(status), updated_at = NOW()";

        try (PreparedStatement ps = conn.prepareStatement(upsertSql)) {
            ps.setString(1, requestId);
            ps.setString(2, examId);
            ps.setString(3, studentId);
            ps.setString(4, studentCode);
            ps.setString(5, studentName);
            ps.setString(6, reason);
            ps.setString(7, status);
            ps.executeUpdate();
        }
        log.info("Applied retake_requests sync: id={}, status={}", requestId, status);
    }

    private JsonNode parseJson(String json) {
        if (json == null || json.isEmpty()) return JsonUtil.createObjectNode();
        return JsonUtil.readTree(json);
    }
}
