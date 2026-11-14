package vn.edu.ptit.exam.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.edu.ptit.exam.common.dto.AuthToken;
import vn.edu.ptit.exam.common.dto.ExamTicket;
import vn.edu.ptit.exam.common.enums.UserRole;
import vn.edu.ptit.exam.common.util.HmacUtil;

import static org.junit.jupiter.api.Assertions.*;

class HmacUtilTest {

    private final String secretKey = "test_super_secret_key_123456";
    private long now;

    @BeforeEach
    void setUp() {
        now = System.currentTimeMillis();
    }

    @Test
    @DisplayName("Should successfully create and verify a valid AuthToken")
    void testValidToken() {
        long expiry = now + 4 * 3600 * 1000; // 4 hours
        AuthToken token = new AuthToken("u-001", "sv_01", "Nguyen Van A", UserRole.STUDENT, now, expiry);

        String tokenString = HmacUtil.createToken(token, secretKey);
        assertNotNull(tokenString);
        assertTrue(tokenString.contains("."));

        AuthToken parsed = HmacUtil.parseAndVerifyToken(tokenString, secretKey);
        assertNotNull(parsed);
        assertEquals("u-001", parsed.getUserId());
        assertEquals("sv_01", parsed.getUsername());
        assertEquals("Nguyen Van A", parsed.getFullName());
        assertEquals(UserRole.STUDENT, parsed.getRole());
        assertEquals(expiry, parsed.getExpiresAt());
        assertFalse(parsed.isExpired());
    }

    @Test
    @DisplayName("Should reject token signed with different secret key")
    void testTokenWithWrongKey() {
        long expiry = now + 4 * 3600 * 1000;
        AuthToken token = new AuthToken("u-001", "sv_01", "Nguyen Van A", UserRole.STUDENT, now, expiry);

        String tokenString = HmacUtil.createToken(token, secretKey);
        AuthToken parsed = HmacUtil.parseAndVerifyToken(tokenString, "wrong_secret_key");
        assertNull(parsed, "Token verified with wrong key should be null");
    }

    @Test
    @DisplayName("Should reject tampered token payload or signature")
    void testTamperedToken() {
        long expiry = now + 4 * 3600 * 1000;
        AuthToken token = new AuthToken("u-001", "sv_01", "Nguyen Van A", UserRole.STUDENT, now, expiry);

        String tokenString = HmacUtil.createToken(token, secretKey);
        String[] parts = tokenString.split("\\.");

        // Tamper signature
        String tamperedSignature = parts[0] + "." + parts[1] + "tampered";
        assertNull(HmacUtil.parseAndVerifyToken(tamperedSignature, secretKey));

        // Tamper payload
        String tamperedPayload = parts[0] + "xyz." + parts[1];
        assertNull(HmacUtil.parseAndVerifyToken(tamperedPayload, secretKey));
    }

    @Test
    @DisplayName("Should reject expired token")
    void testExpiredToken() {
        long expiredTime = now - 1000; // 1 second ago
        AuthToken token = new AuthToken("u-001", "sv_01", "Nguyen Van A", UserRole.STUDENT, now - 5000, expiredTime);

        String tokenString = HmacUtil.createToken(token, secretKey);
        AuthToken parsed = HmacUtil.parseAndVerifyToken(tokenString, secretKey);
        assertNull(parsed, "Expired token should not be accepted");
    }

    @Test
    @DisplayName("Should sign and verify ExamTicket successfully")
    void testSignAndVerifyExamTicket() {
        long startTime = now;
        long deadline = now + 45 * 60 * 1000;
        ExamTicket ticket = new ExamTicket("att-123", "u-001", "exam-999", 123456789L, startTime, deadline, "SERVER_1");

        HmacUtil.signExamTicket(ticket, secretKey);
        assertNotNull(ticket.getSignature());

        boolean isValid = HmacUtil.verifyExamTicket(ticket, secretKey);
        assertTrue(isValid, "Ticket signature should be valid");

        // Verify with wrong key
        assertFalse(HmacUtil.verifyExamTicket(ticket, "different_key"));

        // Tamper ticket deadline (attempt to prolong time)
        ticket.setDeadline(deadline + 100000);
        assertFalse(HmacUtil.verifyExamTicket(ticket, secretKey), "Tampered ticket should be rejected");
    }
}
