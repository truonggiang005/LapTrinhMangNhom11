package vn.edu.ptit.exam.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExamMonitorDto {

    private String examId;
    private String examTitle;
    private int totalAssigned;
    private int notStartedCount;
    private int inProgressCount;
    private int submittedCount;
    private long serverTime;

    public ExamMonitorDto() {
    }

    public ExamMonitorDto(String examId, String examTitle, int totalAssigned, int notStartedCount,
                          int inProgressCount, int submittedCount, long serverTime) {
        this.examId = examId;
        this.examTitle = examTitle;
        this.totalAssigned = totalAssigned;
        this.notStartedCount = notStartedCount;
        this.inProgressCount = inProgressCount;
        this.submittedCount = submittedCount;
        this.serverTime = serverTime;
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

    public int getTotalAssigned() {
        return totalAssigned;
    }

    public void setTotalAssigned(int totalAssigned) {
        this.totalAssigned = totalAssigned;
    }

    public int getNotStartedCount() {
        return notStartedCount;
    }

    public void setNotStartedCount(int notStartedCount) {
        this.notStartedCount = notStartedCount;
    }

    public int getInProgressCount() {
        return inProgressCount;
    }

    public void setInProgressCount(int inProgressCount) {
        this.inProgressCount = inProgressCount;
    }

    public int getSubmittedCount() {
        return submittedCount;
    }

    public void setSubmittedCount(int submittedCount) {
        this.submittedCount = submittedCount;
    }

    public long getServerTime() {
        return serverTime;
    }

    public void setServerTime(long serverTime) {
        this.serverTime = serverTime;
    }
}
