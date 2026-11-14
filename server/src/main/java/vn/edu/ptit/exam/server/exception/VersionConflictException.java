package vn.edu.ptit.exam.server.exception;

import vn.edu.ptit.exam.common.enums.ErrorCode;

public class VersionConflictException extends ExamServerException {

    public VersionConflictException(String message, Object currentRecord) {
        super(ErrorCode.VERSION_CONFLICT, message, currentRecord);
    }
}
