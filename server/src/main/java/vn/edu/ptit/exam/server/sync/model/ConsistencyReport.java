package vn.edu.ptit.exam.server.sync.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConsistencyReport implements Serializable {

    private long timestamp;
    private boolean consistent;
    private Map<String, TableHashReport> tableReports = new HashMap<>();
    private String details;

    public ConsistencyReport() {
    }

    public ConsistencyReport(long timestamp, boolean consistent, Map<String, TableHashReport> tableReports, String details) {
        this.timestamp = timestamp;
        this.consistent = consistent;
        this.tableReports = tableReports;
        this.details = details;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public boolean isConsistent() {
        return consistent;
    }

    public void setConsistent(boolean consistent) {
        this.consistent = consistent;
    }

    public Map<String, TableHashReport> getTableReports() {
        return tableReports;
    }

    public void setTableReports(Map<String, TableHashReport> tableReports) {
        this.tableReports = tableReports;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }
}
