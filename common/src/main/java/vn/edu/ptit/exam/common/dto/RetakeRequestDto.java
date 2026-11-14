package vn.edu.ptit.exam.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RetakeRequestDto {

    private String id;
    private String examId;
    private String examTitle;
    private String subject;
    private String studentId;
    private String studentCode;
    private String studentName;
    private String reason;
    private String status; // PENDING, APPROVED, REJECTED
    private Long createdAt;
    private Long updatedAt;

    public RetakeRequestDto() {
    }

    public RetakeRequestDto(String id, String examId, String examTitle, String subject,
                            String studentId, String studentCode, String studentName,
                            String reason, String status, Long createdAt, Long updatedAt) {
        this.id = id;
        this.examId = examId;
        this.examTitle = examTitle;
        this.subject = subject;
        this.studentId = studentId;
        this.studentCode = studentCode;
        this.studentName = studentName;
        this.reason = reason;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public String getStudentName() {
        return studentName;
    }

    public void setStudentName(String studentName) {
        this.studentName = studentName;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
