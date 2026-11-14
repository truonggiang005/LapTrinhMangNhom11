package vn.edu.ptit.exam.common.enums;

/**
 * Message types catalog according to Section 14.1 of the specification.
 */
public enum MessageType {
    // Connection & Health
    PING,
    PONG,

    // Authentication & Session
    LOGIN,
    RESUME_SESSION,
    SESSION_REPLACED,

    // Exam Flow
    LIST_EXAMS,
    START_EXAM,
    QUEUED,
    SAVE_ANSWERS,
    SUBMIT,
    GET_RESULT,
    LIST_HISTORY,

    // Lecturer Operations
    QUESTION_LIST,
    QUESTION_CREATE,
    QUESTION_UPDATE,
    QUESTION_DELETE,
    EXAM_CREATE,
    EXAM_MONITOR,
    GET_SCORES,
    GET_STATS,

    // Admin Operations
    USER_LIST,
    USER_CREATE,
    USER_UPDATE,
    USER_RESET_PASSWORD,
    USER_LOCK,
    USER_BATCH,
    ADMIN_STATUS,
    ADMIN_SET_ALGO,
    LOG_QUERY,

    // Failover & Load Balancing
    SERVER_SWITCHING,
    RESUME_REQUEST,
    RESUME_OK,
    STATUS_REQUEST,

    // Retake Flow
    EXAM_RETAKE_REQUEST,
    EXAM_RETAKE_LIST,
    EXAM_RETAKE_DECIDE,

    // Inter-Server Sync
    SYNC_HELLO,
    SYNC_BATCH,
    SYNC_ACK,
    SYNC_CONSISTENCY_REQUEST,
    SYNC_CONSISTENCY_RESPONSE,
    SYNC_TABLE_SYNC
}
