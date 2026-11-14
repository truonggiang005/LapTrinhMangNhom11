package vn.edu.ptit.exam.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExamReadinessDto {

    private String examId;
    private int replicatedServers;
    private int totalServers;
    private boolean ready;
    private String statusText; // e.g. "Sẵn sàng 4/4 Server"

    public ExamReadinessDto() {
    }

    public ExamReadinessDto(String examId, int replicatedServers, int totalServers, boolean ready, String statusText) {
        this.examId = examId;
        this.replicatedServers = replicatedServers;
        this.totalServers = totalServers;
        this.ready = ready;
        this.statusText = statusText;
    }

    public String getExamId() {
        return examId;
    }

    public void setExamId(String examId) {
        this.examId = examId;
    }

    public int getReplicatedServers() {
        return replicatedServers;
    }

    public void setReplicatedServers(int replicatedServers) {
        this.replicatedServers = replicatedServers;
    }

    public int getTotalServers() {
        return totalServers;
    }

    public void setTotalServers(int totalServers) {
        this.totalServers = totalServers;
    }

    public boolean isReady() {
        return ready;
    }

    public void setReady(boolean ready) {
        this.ready = ready;
    }

    public String getStatusText() {
        return statusText;
    }

    public void setStatusText(String statusText) {
        this.statusText = statusText;
    }
}
