package vn.edu.ptit.exam.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class SaveAnswersResponse {

    private int savedCount;
    private long serverTime;
    private long remainingSeconds;

    public SaveAnswersResponse() {
    }

    public SaveAnswersResponse(int savedCount, long serverTime, long remainingSeconds) {
        this.savedCount = savedCount;
        this.serverTime = serverTime;
        this.remainingSeconds = remainingSeconds;
    }

    public int getSavedCount() {
        return savedCount;
    }

    public void setSavedCount(int savedCount) {
        this.savedCount = savedCount;
    }

    public long getServerTime() {
        return serverTime;
    }

    public void setServerTime(long serverTime) {
        this.serverTime = serverTime;
    }

    public long getRemainingSeconds() {
        return remainingSeconds;
    }

    public void setRemainingSeconds(long remainingSeconds) {
        this.remainingSeconds = remainingSeconds;
    }
}
