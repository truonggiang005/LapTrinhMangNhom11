package vn.edu.ptit.exam.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import vn.edu.ptit.exam.common.enums.ErrorCode;
import vn.edu.ptit.exam.common.enums.MessageType;

/**
 * Standard protocol message DTO according to Section 1.2 of the specification.
 * Each message is encoded as a single UTF-8 JSON line ending with '\n'.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Message {

    private String type;
    private String requestId;
    private String token;
    private String attemptId;
    private Long seq;
    private String origin;
    private Object payload;
    private String status;        // "OK" or "ERROR"
    private Integer code;         // Error code or 200
    private String message;
    private Long serverTime;
    private Integer retryAfter;   // Seconds to wait before retry (for BUSY 429)

    public Message() {
    }

    public Message(String type, String requestId) {
        this.type = type;
        this.requestId = requestId;
    }

    // --- Helper Factory Methods ---

    public static Message request(MessageType type, String requestId) {
        return new Message(type.name(), requestId);
    }

    public static Message ok(String type, String requestId, Object payload) {
        Message msg = new Message(type, requestId);
        msg.setStatus("OK");
        msg.setCode(200);
        msg.setPayload(payload);
        msg.setServerTime(System.currentTimeMillis());
        return msg;
    }

    public static Message ok(MessageType type, String requestId, Object payload) {
        return ok(type.name(), requestId, payload);
    }

    public static Message error(String type, String requestId, ErrorCode errorCode) {
        return error(type, requestId, errorCode, errorCode.getDefaultMessage());
    }

    public static Message error(MessageType type, String requestId, ErrorCode errorCode) {
        return error(type.name(), requestId, errorCode, errorCode.getDefaultMessage());
    }

    public static Message error(String type, String requestId, ErrorCode errorCode, String customMessage) {
        Message msg = new Message(type, requestId);
        msg.setStatus("ERROR");
        msg.setCode(errorCode.getCode());
        msg.setMessage(customMessage != null ? customMessage : errorCode.getDefaultMessage());
        msg.setServerTime(System.currentTimeMillis());
        return msg;
    }

    public static Message error(MessageType type, String requestId, ErrorCode errorCode, String customMessage) {
        return error(type.name(), requestId, errorCode, customMessage);
    }

    public static Message errorWithPayload(MessageType type, String requestId, ErrorCode errorCode, String customMessage, Object payload) {
        Message msg = error(type.name(), requestId, errorCode, customMessage);
        msg.setPayload(payload);
        return msg;
    }

    public static Message busy(String type, String requestId, int retryAfterSeconds) {
        Message msg = error(type, requestId, ErrorCode.BUSY);
        msg.setRetryAfter(retryAfterSeconds);
        return msg;
    }

    public static Message busy(MessageType type, String requestId, int retryAfterSeconds) {
        return busy(type.name(), requestId, retryAfterSeconds);
    }

    public boolean isOk() {
        return "OK".equalsIgnoreCase(status);
    }

    public boolean isError() {
        return "ERROR".equalsIgnoreCase(status);
    }

    // --- Getters and Setters ---

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public void setType(MessageType type) {
        this.type = type != null ? type.name() : null;
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getAttemptId() {
        return attemptId;
    }

    public void setAttemptId(String attemptId) {
        this.attemptId = attemptId;
    }

    public Long getSeq() {
        return seq;
    }

    public void setSeq(Long seq) {
        this.seq = seq;
    }

    public Object getPayload() {
        return payload;
    }

    public void setPayload(Object payload) {
        this.payload = payload;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Long getServerTime() {
        return serverTime;
    }

    public void setServerTime(Long serverTime) {
        this.serverTime = serverTime;
    }

    public Integer getRetryAfter() {
        return retryAfter;
    }

    public void setRetryAfter(Integer retryAfter) {
        this.retryAfter = retryAfter;
    }

    @Override
    public String toString() {
        return "Message{" +
                "type='" + type + '\'' +
                ", requestId='" + requestId + '\'' +
                ", status='" + status + '\'' +
                ", code=" + code +
                ", message='" + message + '\'' +
                '}';
    }
}
