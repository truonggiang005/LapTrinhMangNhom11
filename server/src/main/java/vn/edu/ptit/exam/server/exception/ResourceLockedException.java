package vn.edu.ptit.exam.server.exception;

import vn.edu.ptit.exam.common.enums.ErrorCode;

public class ResourceLockedException extends ExamServerException {

    public ResourceLockedException(String message) {
        super(ErrorCode.IN_USE_BY_ACTIVE_EXAM, message);
    }
}
