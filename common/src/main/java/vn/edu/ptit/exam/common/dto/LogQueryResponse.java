package vn.edu.ptit.exam.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class LogQueryResponse {

    private String logType;
    private int totalRows;
    private int page;
    private int pageSize;
    private List<LogEntryDto> entries = new ArrayList<>();

    public LogQueryResponse() {
    }

    public LogQueryResponse(String logType, int totalRows, int page, int pageSize, List<LogEntryDto> entries) {
        this.logType = logType;
        this.totalRows = totalRows;
        this.page = page;
        this.pageSize = pageSize;
        this.entries = entries != null ? entries : new ArrayList<>();
    }

    public String getLogType() {
        return logType;
    }

    public void setLogType(String logType) {
        this.logType = logType;
    }

    public int getTotalRows() {
        return totalRows;
    }

    public void setTotalRows(int totalRows) {
        this.totalRows = totalRows;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public List<LogEntryDto> getEntries() {
        return entries;
    }

    public void setEntries(List<LogEntryDto> entries) {
        this.entries = entries;
    }
}
