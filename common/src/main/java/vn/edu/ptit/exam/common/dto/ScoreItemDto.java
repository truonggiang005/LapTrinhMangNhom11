package vn.edu.ptit.exam.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScoreItemDto {

    private String studentId;
    private String studentCode;
    private String fullName;
    private Double score;
    private Integer correctCount;
    private Integer totalQuestions;
    private Long submittedAt;
    private boolean isLate;
    private String status;

    public ScoreItemDto() {
    }

    public ScoreItemDto(String studentId, String studentCode, String fullName, Double score,
                        Integer correctCount, Integer totalQuestions, Long submittedAt, boolean isLate, String status) {
        this.studentId = studentId;
        this.studentCode = studentCode;
        this.fullName = fullName;
        this.score = score;
        this.correctCount = correctCount;
        this.totalQuestions = totalQuestions;
        this.submittedAt = submittedAt;
        this.isLate = isLate;
        this.status = status;
    }

    public String getStudentId() {
        return studentId;
    }

    public void setStudentId(String studentId) {
        this.studentId = studentId;
    }

    public String getStudentCode() {
        return studentCode;
    }

    public void setStudentCode(String studentCode) {
        this.studentCode = studentCode;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
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

    public Long getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Long submittedAt) {
        this.submittedAt = submittedAt;
    }

    public boolean isLate() {
        return isLate;
    }

    public void setLate(boolean late) {
        isLate = late;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
