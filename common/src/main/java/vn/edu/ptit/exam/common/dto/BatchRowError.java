package vn.edu.ptit.exam.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class BatchRowError {

    private int rowIndex;
    private String username;
    private String errorMessage;

    public BatchRowError() {
    }

    public BatchRowError(int rowIndex, String username, String errorMessage) {
        this.rowIndex = rowIndex;
        this.username = username;
        this.errorMessage = errorMessage;
    }

    public int getRowIndex() {
        return rowIndex;
    }

    public void setRowIndex(int rowIndex) {
        this.rowIndex = rowIndex;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
