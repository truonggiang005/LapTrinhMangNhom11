package vn.edu.ptit.exam.server.sync.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.Serializable;

/**
 * Representation of a row in the sync_log table.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SyncLogEntry implements Serializable {

    private String id;
    private String origin;
    private long seq;
    private String opId;
    private String tableName;
    private String recordId;
    private String opType;
    private int version;
    private String payloadJson;
    private String status; // PENDING, DONE
    private long createdAt;

    public SyncLogEntry() {
    }

    public SyncLogEntry(String id, String origin, long seq, String opId, String tableName,
                        String recordId, String opType, int version, String payloadJson,
                        String status, long createdAt) {
        this.id = id;
        this.origin = origin;
        this.seq = seq;
        this.opId = opId;
        this.tableName = tableName;
        this.recordId = recordId;
        this.opType = opType;
        this.version = version;
        this.payloadJson = payloadJson;
        this.status = status;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public long getSeq() {
        return seq;
    }

    public void setSeq(long seq) {
        this.seq = seq;
    }

    public String getOpId() {
        return opId;
    }

    public void setOpId(String opId) {
        this.opId = opId;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getRecordId() {
        return recordId;
    }

    public void setRecordId(String recordId) {
        this.recordId = recordId;
    }

    public String getOpType() {
        return opType;
    }

    public void setOpType(String opType) {
        this.opType = opType;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return "SyncLogEntry{" +
                "opId='" + opId + '\'' +
                ", origin='" + origin + '\'' +
                ", seq=" + seq +
                ", table='" + tableName + '\'' +
                ", opType='" + opType + '\'' +
                ", version=" + version +
                '}';
    }
}
