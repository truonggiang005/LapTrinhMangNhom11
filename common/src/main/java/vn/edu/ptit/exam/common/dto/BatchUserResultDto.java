package vn.edu.ptit.exam.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class BatchUserResultDto {

    private int totalRows;
    private int importedCount;
    private int failedCount;
    private List<BatchRowError> errors = new ArrayList<>();

    public BatchUserResultDto() {
    }

    public BatchUserResultDto(int totalRows, int importedCount, int failedCount, List<BatchRowError> errors) {
        this.totalRows = totalRows;
        this.importedCount = importedCount;
        this.failedCount = failedCount;
        this.errors = errors != null ? errors : new ArrayList<>();
    }

    public int getTotalRows() {
        return totalRows;
    }

    public void setTotalRows(int totalRows) {
        this.totalRows = totalRows;
    }

    public int getImportedCount() {
        return importedCount;
    }

    public void setImportedCount(int importedCount) {
        this.importedCount = importedCount;
    }

    public int getFailedCount() {
        return failedCount;
    }

    public void setFailedCount(int failedCount) {
        this.failedCount = failedCount;
    }

    public List<BatchRowError> getErrors() {
        return errors;
    }

    public void setErrors(List<BatchRowError> errors) {
        this.errors = errors;
    }
}
