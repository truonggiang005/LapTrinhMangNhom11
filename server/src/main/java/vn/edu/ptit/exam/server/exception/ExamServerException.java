package vn.edu.ptit.exam.server.exception;

import vn.edu.ptit.exam.common.enums.ErrorCode;

public class ExamServerException extends Exception {

    private final ErrorCode errorCode;
    private final Object payload;

    public ExamServerException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.payload = null;
    }

    public ExamServerException(ErrorCode errorCode, String message, Object payload) {
        super(message);
        this.errorCode = errorCode;
        this.payload = payload;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Object getPayload() {
        return payload;
    }
}
