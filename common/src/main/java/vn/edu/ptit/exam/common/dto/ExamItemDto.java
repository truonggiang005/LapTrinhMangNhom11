package vn.edu.ptit.exam.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExamItemDto {

    private String id;
    private String title;
    private String subject;
    private int durationMinutes;
    private long startTime;
    private long endTime;
    private String status; // NOT_OPEN, OPEN, IN_PROGRESS, SUBMITTED, CLOSED
    private int totalQuestions;
    private Long remainingSeconds;
    private String attemptId;
    private Double score;
    private String retakeStatus; // PENDING, APPROVED, REJECTED

    public ExamItemDto() {
    }

    public ExamItemDto(String id, String title, String subject, int durationMinutes,
                       long startTime, long endTime, String status, int totalQuestions) {
        this.id = id;
        this.title = title;
        this.subject = subject;
        this.durationMinutes = durationMinutes;
        this.startTime = startTime;
        this.endTime = endTime;
        this.status = status;
        this.totalQuestions = totalQuestions;
    }

    // Getters and Setters

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(int durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    public void setEndTime(long endTime) {
        this.endTime = endTime;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getTotalQuestions() {
        return totalQuestions;
    }

    public void setTotalQuestions(int totalQuestions) {
        this.totalQuestions = totalQuestions;
    }

    public Long getRemainingSeconds() {
        return remainingSeconds;
    }

    public void setRemainingSeconds(Long remainingSeconds) {
        this.remainingSeconds = remainingSeconds;
    }

    public String getAttemptId() {
        return attemptId;
    }

    public void setAttemptId(String attemptId) {
        this.attemptId = attemptId;
    }

    public Double getScore() {
        return score;
    }

    public void setScore(Double score) {
        this.score = score;
    }

    public String getRetakeStatus() {
        return retakeStatus;
    }

    public void setRetakeStatus(String retakeStatus) {
        this.retakeStatus = retakeStatus;
    }
}
