package vn.edu.ptit.exam.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.edu.ptit.exam.common.dto.Message;
import vn.edu.ptit.exam.common.enums.ErrorCode;
import vn.edu.ptit.exam.common.enums.MessageType;
import vn.edu.ptit.exam.common.util.JsonUtil;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonUtilTest {

    @Test
    @DisplayName("Should serialize and deserialize OK Message correctly without null fields")
    void testOkMessageSerialization() {
        Message msg = Message.ok(MessageType.LOGIN, "req-001", Map.of("username", "sv_01", "role", "STUDENT"));
        msg.setToken("sample_token_xyz");

        String json = JsonUtil.toJson(msg);
        assertNotNull(json);
        assertTrue(json.contains("\"type\":\"LOGIN\""));
        assertTrue(json.contains("\"requestId\":\"req-001\""));
        assertTrue(json.contains("\"status\":\"OK\""));
        assertTrue(json.contains("\"code\":200"));
        assertTrue(json.contains("\"username\":\"sv_01\""));
        assertFalse(json.contains("\"retryAfter\""), "Null fields should be excluded from JSON");

        Message deserialized = JsonUtil.fromJson(json, Message.class);
        assertNotNull(deserialized);
        assertEquals("LOGIN", deserialized.getType());
        assertEquals("req-001", deserialized.getRequestId());
        assertEquals("OK", deserialized.getStatus());
        assertEquals(200, deserialized.getCode());
        assertEquals("sample_token_xyz", deserialized.getToken());
        assertTrue(deserialized.isOk());
    }

    @Test
    @DisplayName("Should serialize and deserialize ERROR Message with ErrorCode")
    void testErrorMessageSerialization() {
        Message msg = Message.error(MessageType.START_EXAM, "req-002", ErrorCode.EXAM_NOT_OPEN);

        String json = JsonUtil.toJson(msg);
        assertNotNull(json);
        assertTrue(json.contains("\"status\":\"ERROR\""));
        assertTrue(json.contains("\"code\":425"));
        assertTrue(json.contains(ErrorCode.EXAM_NOT_OPEN.getDefaultMessage()));

        Message deserialized = JsonUtil.fromJson(json, Message.class);
        assertNotNull(deserialized);
        assertEquals(425, deserialized.getCode());
        assertEquals("ERROR", deserialized.getStatus());
        assertTrue(deserialized.isError());
    }

    @Test
    @DisplayName("Should serialize BUSY Message with retryAfter field")
    void testBusyMessageSerialization() {
        Message msg = Message.busy(MessageType.START_EXAM, "req-003", 5);

        String json = JsonUtil.toJson(msg);
        assertNotNull(json);
        assertTrue(json.contains("\"code\":429"));
        assertTrue(json.contains("\"retryAfter\":5"));

        Message deserialized = JsonUtil.fromJson(json, Message.class);
        assertNotNull(deserialized);
        assertEquals(429, deserialized.getCode());
        assertEquals(5, deserialized.getRetryAfter());
    }

    @Test
    @DisplayName("Should preserve deterministic SUBMIT requestId format SUBMIT-<attemptId>")
    void testSubmitMessageRequestId() {
        String attemptId = "att-abc-xyz-123";
        String expectedRequestId = "SUBMIT-" + attemptId;

        Message submitMsg = new Message(MessageType.SUBMIT.name(), expectedRequestId);
        submitMsg.setAttemptId(attemptId);

        String json = JsonUtil.toJson(submitMsg);
        assertTrue(json.contains("\"requestId\":\"SUBMIT-att-abc-xyz-123\""));

        Message parsed = JsonUtil.fromJson(json, Message.class);
        assertEquals(expectedRequestId, parsed.getRequestId());
        assertEquals(attemptId, parsed.getAttemptId());
    }
}
