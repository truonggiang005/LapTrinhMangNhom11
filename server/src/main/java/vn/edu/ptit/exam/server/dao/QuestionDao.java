package vn.edu.ptit.exam.server.dao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.common.dto.OptionDto;
import vn.edu.ptit.exam.common.dto.QuestionDto;
import vn.edu.ptit.exam.common.dto.QuestionListResponse;
import vn.edu.ptit.exam.common.enums.ErrorCode;
import vn.edu.ptit.exam.common.model.Question;
import vn.edu.ptit.exam.common.model.QuestionOption;
import vn.edu.ptit.exam.server.db.DatabaseManager;
import vn.edu.ptit.exam.server.exception.ExamServerException;
import vn.edu.ptit.exam.server.exception.ResourceLockedException;
import vn.edu.ptit.exam.server.exception.VersionConflictException;
import vn.edu.ptit.exam.server.sync.SyncLogWriter;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DAO for questions and options, with in-memory caching of exam questions and answer keys.
 * Supports optimistic concurrency control (baseVersion) and active exam protection (Invariant 11).
 */
public class QuestionDao {

    private static final Logger log = LoggerFactory.getLogger(QuestionDao.class);
    private final DatabaseManager dbManager;

    // In-memory cache for exam questions: examId -> List<Question>
    private final Map<String, List<Question>> examQuestionsCache = new ConcurrentHashMap<>();

    public QuestionDao(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    /**
     * Loads exam questions and options from cache, or fetches from DB if not cached.
     */
    public List<Question> getExamQuestions(String examId) throws SQLException {
        List<Question> cached = examQuestionsCache.get(examId);
        if (cached != null) {
            return cached;
        }

        List<Question> questions = loadExamQuestionsFromDb(examId);
        if (!questions.isEmpty()) {
            examQuestionsCache.put(examId, questions);
        }
        return questions;
    }

    /**
     * Returns a map of questionId -> correctOptionId for quick scoring.
     */
    public Map<String, String> getCorrectAnswerMap(String examId) throws SQLException {
        List<Question> questions = getExamQuestions(examId);
        Map<String, String> answerKey = new HashMap<>();
        for (Question q : questions) {
            for (QuestionOption opt : q.getOptions()) {
                if (opt.isCorrect()) {
                    answerKey.put(q.getId(), opt.getId());
                    break;
                }
            }
        }
        return answerKey;
    }

    private List<Question> loadExamQuestionsFromDb(String examId) throws SQLException {
        String sql = "SELECT eq.question_order, q.id AS q_id, q.subject, q.chapter, q.difficulty, q.content, q.version AS q_version, " +
                     "qo.id AS opt_id, qo.option_key, qo.content AS opt_content, qo.is_correct, qo.order_num " +
                     "FROM exam_questions eq " +
                     "JOIN questions q ON eq.question_id = q.id " +
                     "JOIN question_options qo ON qo.question_id = q.id " +
                     "WHERE eq.exam_id = ? AND q.is_deleted = FALSE " +
                     "ORDER BY eq.question_order ASC, qo.order_num ASC";

        Map<String, Question> questionMap = new LinkedHashMap<>();

        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, examId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String qId = rs.getString("q_id");
                    Question question = questionMap.computeIfAbsent(qId, k -> {
                        try {
                            Question q = new Question();
                            q.setId(qId);
                            q.setSubject(rs.getString("subject"));
                            q.setChapter(rs.getInt("chapter"));
                            q.setDifficulty(rs.getString("difficulty"));
                            q.setContent(rs.getString("content"));
                            q.setVersion(rs.getInt("q_version"));
                            return q;
                        } catch (SQLException e) {
                            throw new RuntimeException(e);
                        }
                    });

                    QuestionOption option = new QuestionOption(
                            rs.getString("opt_id"),
                            qId,
                            rs.getString("option_key"),
                            rs.getString("opt_content"),
                            rs.getBoolean("is_correct"),
                            rs.getInt("order_num")
                    );
                    question.getOptions().add(option);
                }
            }
        }

        return new ArrayList<>(questionMap.values());
    }

    public void invalidateCache(String examId) {
        examQuestionsCache.remove(examId);
    }

    public void invalidateAllCaches() {
        examQuestionsCache.clear();
    }

    // =========================================================================
    // LECTURER / ADMIN QUESTION CRUD & MANAGEMENT
    // =========================================================================

    /**
     * Lists questions with filtering and pagination. Excludes soft-deleted questions.
     */
    public QuestionListResponse listQuestions(String keyword, String subject, Integer chapter,
                                              String difficulty, int page, int pageSize) throws SQLException {
        StringBuilder whereClause = new StringBuilder("WHERE q.is_deleted = FALSE ");
        List<Object> params = new ArrayList<>();

        if (keyword != null && !keyword.trim().isEmpty()) {
            whereClause.append("AND LOWER(q.content) LIKE ? ");
            params.add("%" + keyword.trim().toLowerCase() + "%");
        }
        if (subject != null && !subject.trim().isEmpty()) {
            whereClause.append("AND q.subject = ? ");
            params.add(subject.trim());
        }
        if (chapter != null && chapter > 0) {
            whereClause.append("AND q.chapter = ? ");
            params.add(chapter);
        }
        if (difficulty != null && !difficulty.trim().isEmpty()) {
            whereClause.append("AND q.difficulty = ? ");
            params.add(difficulty.trim().toUpperCase());
        }

        // Count total
        String countSql = "SELECT COUNT(*) FROM questions q " + whereClause;
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

        // Query paginated questions
        String querySql = "SELECT q.id, q.subject, q.chapter, q.difficulty, q.content, q.version " +
                          "FROM questions q " + whereClause +
                          "ORDER BY q.created_at DESC LIMIT ? OFFSET ?";

        List<QuestionDto> questions = new ArrayList<>();
        Map<String, QuestionDto> dtoMap = new LinkedHashMap<>();

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
                    QuestionDto dto = new QuestionDto();
                    dto.setId(rs.getString("id"));
                    dto.setSubject(rs.getString("subject"));
                    dto.setChapter(rs.getInt("chapter"));
                    dto.setDifficulty(rs.getString("difficulty"));
                    dto.setContent(rs.getString("content"));
                    dto.setVersion(rs.getInt("version"));
                    dto.setOptions(new ArrayList<>());
                    dtoMap.put(dto.getId(), dto);
                    questions.add(dto);
                }
            }
        }

        // Fetch options for retrieved questions
        if (!dtoMap.isEmpty()) {
            String placeholders = String.join(",", Collections.nCopies(dtoMap.size(), "?"));
            String optionsSql = "SELECT id, question_id, option_key, content, is_correct, order_num " +
                                "FROM question_options WHERE question_id IN (" + placeholders + ") " +
                                "ORDER BY question_id, order_num ASC";
            try (Connection conn = dbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(optionsSql)) {
                int pIdx = 1;
                for (String qId : dtoMap.keySet()) {
                    ps.setString(pIdx++, qId);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String qId = rs.getString("question_id");
                        QuestionDto q = dtoMap.get(qId);
                        if (q != null) {
                            OptionDto opt = new OptionDto(
                                    rs.getString("id"),
                                    rs.getString("option_key"),
                                    rs.getString("content"),
                                    rs.getBoolean("is_correct")
                            );
                            q.getOptions().add(opt);
                        }
                    }
                }
            }
        }

        return new QuestionListResponse(questions, totalRows, safePage, safePageSize);
    }

    /**
     * Finds a single question with options by ID.
     */
    public QuestionDto findQuestionById(String questionId) throws SQLException {
        String sql = "SELECT id, subject, chapter, difficulty, content, is_deleted, version FROM questions WHERE id = ?";
        QuestionDto dto = null;

        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, questionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    dto = new QuestionDto();
                    dto.setId(rs.getString("id"));
                    dto.setSubject(rs.getString("subject"));
                    dto.setChapter(rs.getInt("chapter"));
                    dto.setDifficulty(rs.getString("difficulty"));
                    dto.setContent(rs.getString("content"));
                    dto.setVersion(rs.getInt("version"));
                    dto.setOptions(new ArrayList<>());
                }
            }
        }

        if (dto != null) {
            String optSql = "SELECT id, option_key, content, is_correct, order_num FROM question_options WHERE question_id = ? ORDER BY order_num ASC";
            try (Connection conn = dbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(optSql)) {
                ps.setString(1, questionId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        dto.getOptions().add(new OptionDto(
                                rs.getString("id"),
                                rs.getString("option_key"),
                                rs.getString("content"),
                                rs.getBoolean("is_correct")
                        ));
                    }
                }
            }
        }

        return dto;
    }

    /**
     * Invariant 11: Checks if a question belongs to an ongoing or active exam window.
     */
    public boolean isQuestionInActiveExam(String questionId, long serverTime) throws SQLException {
        Timestamp now = new Timestamp(serverTime);
        String sql = "SELECT 1 FROM exam_questions eq " +
                     "JOIN exams e ON eq.exam_id = e.id " +
                     "WHERE eq.question_id = ? AND (? BETWEEN e.start_time AND e.end_time OR e.status = 'OPEN') " +
                     "LIMIT 1";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, questionId);
            ps.setTimestamp(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Creates a new question transactionally with options and sync_log entry.
     */
    public QuestionDto createQuestion(QuestionDto dto, String createdBy, SyncLogWriter syncLogWriter)
            throws SQLException, ExamServerException {
        validateQuestion(dto);

        String id = dto.getId() != null ? dto.getId() : UUID.randomUUID().toString();
        dto.setId(id);
        dto.setVersion(1);

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String insertSql = "INSERT INTO questions (id, subject, chapter, difficulty, content, is_deleted, version, created_by, created_at, updated_at) " +
                                  "VALUES (?, ?, ?, ?, ?, FALSE, 1, ?, NOW(), NOW())";
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    ps.setString(1, id);
                    ps.setString(2, dto.getSubject());
                    ps.setInt(3, dto.getChapter() != null ? dto.getChapter() : 1);
                    ps.setString(4, dto.getDifficulty() != null ? dto.getDifficulty().toUpperCase() : "MEDIUM");
                    ps.setString(5, dto.getContent());
                    ps.setString(6, createdBy);
                    ps.executeUpdate();
                }

                String insertOptSql = "INSERT INTO question_options (id, question_id, option_key, content, is_correct, order_num) " +
                                      "VALUES (?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(insertOptSql)) {
                    int order = 1;
                    char keyChar = 'A';
                    for (OptionDto opt : dto.getOptions()) {
                        String optId = opt.getId() != null ? opt.getId() : UUID.randomUUID().toString();
                        opt.setId(optId);
                        String optKey = opt.getOptionKey() != null ? opt.getOptionKey() : String.valueOf(keyChar++);
                        opt.setOptionKey(optKey);

                        ps.setString(1, optId);
                        ps.setString(2, id);
                        ps.setString(3, optKey);
                        ps.setString(4, opt.getContent());
                        ps.setBoolean(5, Boolean.TRUE.equals(opt.getIsCorrect()));
                        ps.setInt(6, order++);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }

                // Invariant 3: Write sync_log in the same transaction
                if (syncLogWriter != null) {
                    syncLogWriter.writeSyncLog(conn, "questions", id, "QUESTION_CREATE", 1, dto);
                }

                conn.commit();
                log.info("Created question [id={}, subject={}, version=1]", id, dto.getSubject());
                return dto;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /**
     * Updates an existing question with optimistic locking (baseVersion) and Invariant 11 check.
     */
    public QuestionDto updateQuestion(QuestionDto dto, int baseVersion, SyncLogWriter syncLogWriter, long serverTime)
            throws SQLException, ExamServerException {
        if (dto.getId() == null) {
            throw new ExamServerException(ErrorCode.BAD_REQUEST, "Thiếu mã câu hỏi cần sửa");
        }

        // Invariant 11: Protect active exam
        if (isQuestionInActiveExam(dto.getId(), serverTime)) {
            throw new ResourceLockedException("Câu hỏi đang thuộc ca thi đang diễn ra, không thể sửa!");
        }

        QuestionDto current = findQuestionById(dto.getId());
        if (current == null) {
            throw new ExamServerException(ErrorCode.NOT_FOUND, "Không tìm thấy câu hỏi với mã " + dto.getId());
        }

        // Optimistic concurrency check
        if (current.getVersion() != baseVersion) {
            log.warn("Question {} version conflict: baseVersion={} but current={}", dto.getId(), baseVersion, current.getVersion());
            throw new VersionConflictException("Xung đột phiên bản dữ liệu: câu hỏi đã được sửa bởi người khác", current);
        }

        validateQuestion(dto);
        int newVersion = baseVersion + 1;
        dto.setVersion(newVersion);

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String updateSql = "UPDATE questions SET subject = ?, chapter = ?, difficulty = ?, content = ?, version = ?, updated_at = NOW() " +
                                  "WHERE id = ? AND version = ?";
                try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                    ps.setString(1, dto.getSubject());
                    ps.setInt(2, dto.getChapter() != null ? dto.getChapter() : 1);
                    ps.setString(3, dto.getDifficulty() != null ? dto.getDifficulty().toUpperCase() : "MEDIUM");
                    ps.setString(4, dto.getContent());
                    ps.setInt(5, newVersion);
                    ps.setString(6, dto.getId());
                    ps.setInt(7, baseVersion);
                    int rows = ps.executeUpdate();
                    if (rows == 0) {
                        QuestionDto fresh = findQuestionById(dto.getId());
                        throw new VersionConflictException("Xung đột phiên bản dữ liệu", fresh);
                    }
                }

                // Replace options
                String deleteOldOpts = "DELETE FROM question_options WHERE question_id = ?";
                try (PreparedStatement ps = conn.prepareStatement(deleteOldOpts)) {
                    ps.setString(1, dto.getId());
                    ps.executeUpdate();
                }

                String insertOptSql = "INSERT INTO question_options (id, question_id, option_key, content, is_correct, order_num) " +
                                      "VALUES (?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(insertOptSql)) {
                    int order = 1;
                    char keyChar = 'A';
                    for (OptionDto opt : dto.getOptions()) {
                        String optId = opt.getId() != null ? opt.getId() : UUID.randomUUID().toString();
                        opt.setId(optId);
                        String optKey = opt.getOptionKey() != null ? opt.getOptionKey() : String.valueOf(keyChar++);
                        opt.setOptionKey(optKey);

                        ps.setString(1, optId);
                        ps.setString(2, dto.getId());
                        ps.setString(3, optKey);
                        ps.setString(4, opt.getContent());
                        ps.setBoolean(5, Boolean.TRUE.equals(opt.getIsCorrect()));
                        ps.setInt(6, order++);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }

                // Invariant 3: Write sync_log in same transaction
                if (syncLogWriter != null) {
                    syncLogWriter.writeSyncLog(conn, "questions", dto.getId(), "QUESTION_UPDATE", newVersion, dto);
                }

                conn.commit();
                invalidateAllCaches();
                log.info("Updated question [id={}, newVersion={}]", dto.getId(), newVersion);
                return dto;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /**
     * Soft-deletes a question (is_deleted = TRUE), increments version, logs sync_log (Invariant 11).
     */
    public void deleteQuestion(String questionId, int baseVersion, SyncLogWriter syncLogWriter, long serverTime)
            throws SQLException, ExamServerException {
        // Invariant 11: Protect active exam
        if (isQuestionInActiveExam(questionId, serverTime)) {
            throw new ResourceLockedException("Câu hỏi đang thuộc ca thi đang diễn ra, không thể xóa!");
        }

        QuestionDto current = findQuestionById(questionId);
        if (current == null) {
            throw new ExamServerException(ErrorCode.NOT_FOUND, "Không tìm thấy câu hỏi với mã " + questionId);
        }

        if (current.getVersion() != baseVersion) {
            throw new VersionConflictException("Xung đột phiên bản dữ liệu: câu hỏi đã thay đổi", current);
        }

        int newVersion = baseVersion + 1;

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String deleteSql = "UPDATE questions SET is_deleted = TRUE, version = ?, updated_at = NOW() " +
                                  "WHERE id = ? AND version = ?";
                try (PreparedStatement ps = conn.prepareStatement(deleteSql)) {
                    ps.setInt(1, newVersion);
                    ps.setString(2, questionId);
                    ps.setInt(3, baseVersion);
                    int rows = ps.executeUpdate();
                    if (rows == 0) {
                        QuestionDto fresh = findQuestionById(questionId);
                        throw new VersionConflictException("Xung đột phiên bản dữ liệu", fresh);
                    }
                }

                Map<String, Object> payload = new HashMap<>();
                payload.put("id", questionId);
                payload.put("deleted", true);
                payload.put("version", newVersion);

                if (syncLogWriter != null) {
                    syncLogWriter.writeSyncLog(conn, "questions", questionId, "QUESTION_DELETE", newVersion, payload);
                }

                conn.commit();
                invalidateAllCaches();
                log.info("Soft-deleted question [id={}, newVersion={}]", questionId, newVersion);
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /**
     * Counts active (non-deleted) questions matching subject, chapter, difficulty.
     */
    public int countAvailableQuestions(String subject, Integer chapter, String difficulty) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM questions WHERE is_deleted = FALSE ");
        List<Object> params = new ArrayList<>();

        if (subject != null && !subject.trim().isEmpty()) {
            sql.append("AND subject = ? ");
            params.add(subject.trim());
        }
        if (chapter != null && chapter > 0) {
            sql.append("AND chapter = ? ");
            params.add(chapter);
        }
        if (difficulty != null && !difficulty.trim().isEmpty()) {
            sql.append("AND difficulty = ? ");
            params.add(difficulty.trim().toUpperCase());
        }

        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        return 0;
    }

    /**
     * Randomly selects active question IDs matching subject, chapter, difficulty.
     */
    public List<String> findRandomQuestionIds(String subject, Integer chapter, String difficulty, int count) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT id FROM questions WHERE is_deleted = FALSE ");
        List<Object> params = new ArrayList<>();

        if (subject != null && !subject.trim().isEmpty()) {
            sql.append("AND subject = ? ");
            params.add(subject.trim());
        }
        if (chapter != null && chapter > 0) {
            sql.append("AND chapter = ? ");
            params.add(chapter);
        }
        if (difficulty != null && !difficulty.trim().isEmpty()) {
            sql.append("AND difficulty = ? ");
            params.add(difficulty.trim().toUpperCase());
        }

        // Note: For deterministic sampling or DB portability, order by RAND()
        sql.append("ORDER BY RAND() LIMIT ?");
        params.add(count);

        List<String> result = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString("id"));
                }
            }
        }
        return result;
    }

    private void validateQuestion(QuestionDto dto) throws ExamServerException {
        if (dto.getContent() == null || dto.getContent().trim().isEmpty()) {
            throw new ExamServerException(ErrorCode.BAD_REQUEST, "Nội dung câu hỏi không được để trống");
        }
        if (dto.getSubject() == null || dto.getSubject().trim().isEmpty()) {
            throw new ExamServerException(ErrorCode.BAD_REQUEST, "Môn học không được để trống");
        }
        if (dto.getOptions() == null || dto.getOptions().size() < 2 || dto.getOptions().size() > 6) {
            throw new ExamServerException(ErrorCode.BAD_REQUEST, "Câu hỏi phải có từ 2 đến 6 phương án");
        }
        int correctCount = 0;
        for (OptionDto opt : dto.getOptions()) {
            if (opt.getContent() == null || opt.getContent().trim().isEmpty()) {
                throw new ExamServerException(ErrorCode.BAD_REQUEST, "Nội dung phương án không được để trống");
            }
            if (Boolean.TRUE.equals(opt.getIsCorrect())) {
                correctCount++;
            }
        }
        if (correctCount != 1) {
            throw new ExamServerException(ErrorCode.BAD_REQUEST, "Câu hỏi phải có đúng 1 đáp án đúng");
        }
    }
}
