package vn.edu.ptit.exam.server.sync.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.Serializable;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TableHashReport implements Serializable {

    private String tableName;
    private long rowCount;
    private String hash;

    public TableHashReport() {
    }

    public TableHashReport(String tableName, long rowCount, String hash) {
        this.tableName = tableName;
        this.rowCount = rowCount;
        this.hash = hash;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public long getRowCount() {
        return rowCount;
    }

    public void setRowCount(long rowCount) {
        this.rowCount = rowCount;
    }

    public String getHash() {
        return hash;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }
}
