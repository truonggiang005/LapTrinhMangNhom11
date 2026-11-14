package vn.edu.ptit.exam.client;

import vn.edu.ptit.exam.common.enums.ErrorCode;

/**
 * Exception thrown when the Exam Server or Load Balancer returns an error status code.
 */
public class ExamClientException extends RuntimeException {

    private final int errorCode;
    private final Integer retryAfter;

    public ExamClientException(int errorCode, String message) {
        this(errorCode, message, null);
    }

    public ExamClientException(int errorCode, String message, Integer retryAfter) {
        super(message + " (code " + errorCode + ")");
        this.errorCode = errorCode;
        this.retryAfter = retryAfter;
    }

    public ExamClientException(ErrorCode errorCode) {
        this(errorCode.getCode(), errorCode.getDefaultMessage(), null);
    }

    public int getErrorCode() {
        return errorCode;
    }

    public Integer getRetryAfter() {
        return retryAfter;
    }
}
