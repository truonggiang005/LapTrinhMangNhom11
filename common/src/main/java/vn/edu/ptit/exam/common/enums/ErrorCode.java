package vn.edu.ptit.exam.common.enums;

/**
 * Standard error codes according to Section 14.2 of the specification.
 */
public enum ErrorCode {
    BAD_REQUEST(400, "BAD_REQUEST", "Thông điệp sai khuôn dạng hoặc dữ liệu không hợp lệ"),
    UNAUTHENTICATED(401, "UNAUTHENTICATED", "Token sai, hết hạn hoặc chưa đăng nhập"),
    FORBIDDEN(403, "FORBIDDEN", "Không đủ quyền truy cập"),
    NOT_ASSIGNED(403, "NOT_ASSIGNED", "Chưa được phân công vào ca thi này"),
    NOT_FOUND(404, "NOT_FOUND", "Không tìm thấy dữ liệu yêu cầu"),
    ATTEMPT_NOT_FOUND(404, "ATTEMPT_NOT_FOUND", "Không tìm thấy thông tin bài làm"),
    ALREADY_SUBMITTED(409, "ALREADY_SUBMITTED", "Bài thi đã được nộp trước đó"),
    VERSION_CONFLICT(409, "VERSION_CONFLICT", "Xung đột phiên bản dữ liệu"),
    SESSION_IN_EXAM(409, "SESSION_IN_EXAM", "Tài khoản đang trong ca thi ở nơi khác"),
    EXAM_CLOSED(410, "EXAM_CLOSED", "Ca thi đã đóng"),
    ATTEMPT_EXPIRED(410, "ATTEMPT_EXPIRED", "Thời gian làm bài đã kết thúc"),
    IN_USE_BY_ACTIVE_EXAM(423, "IN_USE_BY_ACTIVE_EXAM", "Dữ liệu đang được sử dụng trong ca thi đang diễn ra"),
    EXAM_NOT_OPEN(425, "EXAM_NOT_OPEN", "Chưa tới giờ mở ca thi"),
    BUSY(429, "BUSY", "Hệ thống đang bận, vui lòng thử lại sau"),
    INTERNAL(500, "INTERNAL", "Lỗi nội bộ hệ thống"),
    NO_SERVER_AVAILABLE(503, "NO_SERVER_AVAILABLE", "Không có Server nào khả dụng");

    private final int code;
    private final String name;
    private final String defaultMessage;

    ErrorCode(int code, String name, String defaultMessage) {
        this.code = code;
        this.name = name;
        this.defaultMessage = defaultMessage;
    }

    public int getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }

    public static ErrorCode fromCodeAndName(int code, String name) {
        for (ErrorCode ec : values()) {
            if (ec.code == code && ec.name.equalsIgnoreCase(name)) {
                return ec;
            }
        }
        return code >= 500 ? INTERNAL : BAD_REQUEST;
    }
}
