package vn.edu.ptit.exam.common.dto;

import java.util.List;

public class ExamListResponse {

    private int total;
    private int page;
    private int pageSize;
    private List<ExamItemDto> exams;

    public ExamListResponse() {
    }

    public ExamListResponse(int total, int page, int pageSize, List<ExamItemDto> exams) {
        this.total = total;
        this.page = page;
        this.pageSize = pageSize;
        this.exams = exams;
    }

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public int getTotalRows() {
        return total;
    }

    public void setTotalRows(int totalRows) {
        this.total = totalRows;
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

    public List<ExamItemDto> getExams() {
        return exams;
    }

    public void setExams(List<ExamItemDto> exams) {
        this.exams = exams;
    }
}
