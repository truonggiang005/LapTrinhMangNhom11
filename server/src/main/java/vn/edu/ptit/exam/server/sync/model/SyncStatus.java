package vn.edu.ptit.exam.server.sync.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Sync module health and performance status returned for monitoring and ADMIN_STATUS.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SyncStatus implements Serializable {

    private String serverId;
    private long currentSeq;
    private long pendingCount;
    private Map<String, SyncPeerStatus> peerStates = new HashMap<>();
    private ConsistencyReport lastConsistencyCheck;

    public SyncStatus() {
    }

    public SyncStatus(String serverId, long currentSeq, long pendingCount,
                      Map<String, SyncPeerStatus> peerStates, ConsistencyReport lastConsistencyCheck) {
        this.serverId = serverId;
        this.currentSeq = currentSeq;
        this.pendingCount = pendingCount;
        this.peerStates = peerStates;
        this.lastConsistencyCheck = lastConsistencyCheck;
    }

    public String getServerId() {
        return serverId;
    }

    public void setServerId(String serverId) {
        this.serverId = serverId;
    }

    public long getCurrentSeq() {
        return currentSeq;
    }

    public void setCurrentSeq(long currentSeq) {
        this.currentSeq = currentSeq;
    }

    public long getPendingCount() {
        return pendingCount;
    }

    public void setPendingCount(long pendingCount) {
        this.pendingCount = pendingCount;
    }

    public Map<String, SyncPeerStatus> getPeerStates() {
        return peerStates;
    }

    public void setPeerStates(Map<String, SyncPeerStatus> peerStates) {
        this.peerStates = peerStates;
    }

    public ConsistencyReport getLastConsistencyCheck() {
        return lastConsistencyCheck;
    }

    public void setLastConsistencyCheck(ConsistencyReport lastConsistencyCheck) {
        this.lastConsistencyCheck = lastConsistencyCheck;
    }
}
