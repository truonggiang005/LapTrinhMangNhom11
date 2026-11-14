package vn.edu.ptit.exam.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class PageResponse<T> {

    private int totalRows;
    private int page;
    private int pageSize;
    private List<T> items = new ArrayList<>();

    public PageResponse() {
    }

    public PageResponse(int totalRows, int page, int pageSize, List<T> items) {
        this.totalRows = totalRows;
        this.page = page;
        this.pageSize = pageSize;
        this.items = items != null ? items : new ArrayList<>();
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

    public List<T> getItems() {
        return items;
    }

    public void setItems(List<T> items) {
        this.items = items;
    }
}
