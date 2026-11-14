package vn.edu.ptit.exam.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class HistoryListResponse {

    private List<HistoryItemDto> items;
    private int totalRows;
    private int page;
    private int pageSize;

    public HistoryListResponse() {
    }

    public HistoryListResponse(List<HistoryItemDto> items, int totalRows, int page, int pageSize) {
        this.items = items;
        this.totalRows = totalRows;
        this.page = page;
        this.pageSize = pageSize;
    }

    public List<HistoryItemDto> getItems() {
        return items;
    }

    public void setItems(List<HistoryItemDto> items) {
        this.items = items;
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
}
