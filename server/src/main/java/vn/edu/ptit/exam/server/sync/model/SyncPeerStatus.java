package vn.edu.ptit.exam.server.sync.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.Serializable;

/**
 * Tracks synchronization status with an individual peer server.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SyncPeerStatus implements Serializable {

    private String peerId;
    private String status; // ACTIVE, DOWN
    private long lastReceivedSeq;
    private long lastAckedSeq;
    private long lastSyncTime;
    private long latencyMs;

    public SyncPeerStatus() {
    }

    public SyncPeerStatus(String peerId, String status, long lastReceivedSeq, long lastAckedSeq, long lastSyncTime, long latencyMs) {
        this.peerId = peerId;
        this.status = status;
        this.lastReceivedSeq = lastReceivedSeq;
        this.lastAckedSeq = lastAckedSeq;
        this.lastSyncTime = lastSyncTime;
        this.latencyMs = latencyMs;
    }

    public String getPeerId() {
        return peerId;
    }

    public void setPeerId(String peerId) {
        this.peerId = peerId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getLastReceivedSeq() {
        return lastReceivedSeq;
    }

    public void setLastReceivedSeq(long lastReceivedSeq) {
        this.lastReceivedSeq = lastReceivedSeq;
    }

    public long getLastAckedSeq() {
        return lastAckedSeq;
    }

    public void setLastAckedSeq(long lastAckedSeq) {
        this.lastAckedSeq = lastAckedSeq;
    }

    public long getLastSyncTime() {
        return lastSyncTime;
    }

    public void setLastSyncTime(long lastSyncTime) {
        this.lastSyncTime = lastSyncTime;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(long latencyMs) {
        this.latencyMs = latencyMs;
    }
}
