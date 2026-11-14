package vn.edu.ptit.exam.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class QuestionListResponse {

    private List<QuestionDto> questions;
    private int totalRows;
    private int page;
    private int pageSize;

    public QuestionListResponse() {
    }

    public QuestionListResponse(List<QuestionDto> questions, int totalRows, int page, int pageSize) {
        this.questions = questions;
        this.totalRows = totalRows;
        this.page = page;
        this.pageSize = pageSize;
    }

    public List<QuestionDto> getQuestions() {
        return questions;
    }

    public void setQuestions(List<QuestionDto> questions) {
        this.questions = questions;
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
