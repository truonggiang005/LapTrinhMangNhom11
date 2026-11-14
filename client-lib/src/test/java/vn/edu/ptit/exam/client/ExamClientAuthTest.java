package vn.edu.ptit.exam.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.edu.ptit.exam.client.mock.MockExamServer;
import vn.edu.ptit.exam.common.dto.Message;
import vn.edu.ptit.exam.common.enums.ErrorCode;
import vn.edu.ptit.exam.common.enums.MessageType;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class ExamClientAuthTest {

    private MockExamServer mockServer;
    private ExamClient client;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockExamServer();
        mockServer.start();
        client = new ExamClient();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.disconnect();
        }
        if (mockServer != null) {
            mockServer.stop();
        }
    }

    @Test
    void testSuccessfulLogin() throws Exception {
        client.connect("127.0.0.1", mockServer.getPort()).get(5, TimeUnit.SECONDS);

        String token = client.login("student01", "password123").get(5, TimeUnit.SECONDS);

        assertNotNull(token);
        assertEquals("mock-valid-hmac-token", token);
        assertEquals("student01", client.getCurrentUsername());
        assertEquals("STUDENT", client.getCurrentRole());
        assertEquals("Nguyen Van A", client.getCurrentFullName());
    }

    @Test
    void testBusy429AutomaticRetry() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);

        // First attempt returns 429 BUSY with retryAfter=1s, second attempt succeeds
        mockServer.setCustomHandler(MessageType.LOGIN.name(), req -> {
            int current = attempts.incrementAndGet();
            if (current == 1) {
                return Message.busy(MessageType.LOGIN, req.getRequestId(), 1);
            } else {
                Message ok = Message.ok(MessageType.LOGIN, req.getRequestId(), Map.of(
                        "username", "student01",
                        "role", "STUDENT",
                        "fullName", "Nguyen Van A"
                ));
                ok.setToken("retry-success-token");
                return ok;
            }
        });

        client.connect("127.0.0.1", mockServer.getPort()).get(5, TimeUnit.SECONDS);

        String token = client.login("student01", "password123").get(6, TimeUnit.SECONDS);

        assertEquals("retry-success-token", token);
        assertEquals(2, attempts.get(), "Expected exactly 2 attempts due to 429 retry");
    }

    @Test
    void testLoginFailureReturnsError() throws Exception {
        mockServer.setCustomHandler(MessageType.LOGIN.name(), req ->
                Message.error(MessageType.LOGIN, req.getRequestId(), ErrorCode.UNAUTHENTICATED, "Sai tên đăng nhập hoặc mật khẩu")
        );

        client.connect("127.0.0.1", mockServer.getPort()).get(5, TimeUnit.SECONDS);

        ExecutionException ex = assertThrows(ExecutionException.class, () ->
                client.login("wrongUser", "wrongPass").get(5, TimeUnit.SECONDS)
        );

        assertTrue(ex.getCause() instanceof ExamClientException);
        ExamClientException ece = (ExamClientException) ex.getCause();
        assertEquals(ErrorCode.UNAUTHENTICATED.getCode(), ece.getErrorCode());
        assertTrue(ece.getMessage().contains("Sai tên đăng nhập"));
    }
}
