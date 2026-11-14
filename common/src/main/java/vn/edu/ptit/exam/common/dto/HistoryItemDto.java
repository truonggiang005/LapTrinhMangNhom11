package vn.edu.ptit.exam.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class HistoryItemDto {

    private String attemptId;
    private String examId;
    private String examTitle;
    private String subject;
    private long startTime;
    private Long submittedAt;
    private Double score;
    private Integer correctCount;
    private Integer totalQuestions;
    private String status;

    public HistoryItemDto() {
    }

    public HistoryItemDto(String attemptId, String examId, String examTitle, String subject,
                          long startTime, Long submittedAt, Double score,
                          Integer correctCount, Integer totalQuestions, String status) {
        this.attemptId = attemptId;
        this.examId = examId;
        this.examTitle = examTitle;
        this.subject = subject;
        this.startTime = startTime;
        this.submittedAt = submittedAt;
        this.score = score;
        this.correctCount = correctCount;
        this.totalQuestions = totalQuestions;
        this.status = status;
    }

    public String getAttemptId() {
        return attemptId;
    }

    public void setAttemptId(String attemptId) {
        this.attemptId = attemptId;
    }

    public String getExamId() {
        return examId;
    }

    public void setExamId(String examId) {
        this.examId = examId;
    }

    public String getExamTitle() {
        return examTitle;
    }

    public void setExamTitle(String examTitle) {
        this.examTitle = examTitle;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public Long getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Long submittedAt) {
        this.submittedAt = submittedAt;
    }

    public Double getScore() {
        return score;
    }

    public void setScore(Double score) {
        this.score = score;
    }

    public Integer getCorrectCount() {
        return correctCount;
    }

    public void setCorrectCount(Integer correctCount) {
        this.correctCount = correctCount;
    }

    public Integer getTotalQuestions() {
        return totalQuestions;
    }

    public void setTotalQuestions(Integer totalQuestions) {
        this.totalQuestions = totalQuestions;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
