package vn.edu.ptit.exam.common.dto;

/**
 * Exam ticket signed by Server with HMAC-SHA256.
 * Given to Client upon START_EXAM, used for failover recovery if the managing Server goes down.
 */
public class ExamTicket {

    private String attemptId;
    private String studentId;
    private String examId;
    private long seed;
    private long startTime;
    private long deadline;
    private String serverId;
    private String signature;

    public ExamTicket() {
    }

    public ExamTicket(String attemptId, String studentId, String examId, long seed, long startTime, long deadline, String serverId) {
        this.attemptId = attemptId;
        this.studentId = studentId;
        this.examId = examId;
        this.seed = seed;
        this.startTime = startTime;
        this.deadline = deadline;
        this.serverId = serverId;
    }

    /**
     * Data payload to be signed: deterministic string representation.
     */
    public String getSignableString() {
        return attemptId + "|" + studentId + "|" + examId + "|" + seed + "|" + startTime + "|" + deadline + "|" + serverId;
    }

    // Getters and Setters

    public String getAttemptId() {
        return attemptId;
    }

    public void setAttemptId(String attemptId) {
        this.attemptId = attemptId;
    }

    public String getStudentId() {
        return studentId;
    }

    public void setStudentId(String studentId) {
        this.studentId = studentId;
    }

    public String getExamId() {
        return examId;
    }

    public void setExamId(String examId) {
        this.examId = examId;
    }

    public long getSeed() {
        return seed;
    }

    public void setSeed(long seed) {
        this.seed = seed;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public long getDeadline() {
        return deadline;
    }

    public void setDeadline(long deadline) {
        this.deadline = deadline;
    }

    public String getServerId() {
        return serverId;
    }

    public void setServerId(String serverId) {
        this.serverId = serverId;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }
}
