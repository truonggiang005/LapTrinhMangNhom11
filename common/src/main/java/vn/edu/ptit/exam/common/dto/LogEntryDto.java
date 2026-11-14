package vn.edu.ptit.exam.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class LogEntryDto {

    private long timestamp;
    private String logType; // "DISTRIBUTION", "SYNC", "AUDIT"
    private String id;
    private String userId;
    private String action;
    private String targetServer;
    private String status;
    private String details;

    public LogEntryDto() {
    }

    public LogEntryDto(long timestamp, String logType, String id, String userId,
                       String action, String targetServer, String status, String details) {
        this.timestamp = timestamp;
        this.logType = logType;
        this.id = id;
        this.userId = userId;
        this.action = action;
        this.targetServer = targetServer;
        this.status = status;
        this.details = details;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getLogType() {
        return logType;
    }

    public void setLogType(String logType) {
        this.logType = logType;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getTargetServer() {
        return targetServer;
    }

    public void setTargetServer(String targetServer) {
        this.targetServer = targetServer;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }
}
