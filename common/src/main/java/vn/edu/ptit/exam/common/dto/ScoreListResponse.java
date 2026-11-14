package vn.edu.ptit.exam.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScoreListResponse {

    private List<ScoreItemDto> scores;
    private int totalRows;
    private int page;
    private int pageSize;
    private boolean isFinalized;

    public ScoreListResponse() {
    }

    public ScoreListResponse(List<ScoreItemDto> scores, int totalRows, int page, int pageSize, boolean isFinalized) {
        this.scores = scores;
        this.totalRows = totalRows;
        this.page = page;
        this.pageSize = pageSize;
        this.isFinalized = isFinalized;
    }

    public List<ScoreItemDto> getScores() {
        return scores;
    }

    public void setScores(List<ScoreItemDto> scores) {
        this.scores = scores;
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

    public boolean isFinalized() {
        return isFinalized;
    }

    public void setFinalized(boolean finalized) {
        isFinalized = finalized;
    }
}
