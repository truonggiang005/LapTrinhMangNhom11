package vn.edu.ptit.exam.common.util;

import vn.edu.ptit.exam.common.dto.AuthToken;
import vn.edu.ptit.exam.common.dto.ExamTicket;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Utility for HMAC-SHA256 signing and token verification.
 */
public final class HmacUtil {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private HmacUtil() {
    }

    /**
     * Computes the HMAC-SHA256 of the given data using the secret key,
     * returning a Base64-URL-encoded string (without padding).
     */
    public static String computeHmacBase64(String data, String secretKey) {
        if (data == null || secretKey == null) {
            throw new IllegalArgumentException("Data and secretKey cannot be null");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec secretKeySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(secretKeySpec);
            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hmacBytes);
        } catch (Exception e) {
            throw new RuntimeException("Error computing HMAC-SHA256: " + e.getMessage(), e);
        }
    }

    /**
     * Creates a signed authentication token in the format: <payloadBase64>.<signatureBase64>
     */
    public static String createToken(AuthToken token, String secretKey) {
        if (token == null || secretKey == null) {
            throw new IllegalArgumentException("Token and secretKey cannot be null");
        }
        String json = JsonUtil.toJson(token);
        String payloadBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        String signature = computeHmacBase64(payloadBase64, secretKey);
        return payloadBase64 + "." + signature;
    }

    /**
     * Verifies the token signature and returns the AuthToken if valid and not expired.
     * Returns null if signature is invalid or token is expired.
     */
    public static AuthToken parseAndVerifyToken(String tokenString, String secretKey) {
        if (tokenString == null || secretKey == null) {
            return null;
        }
        String[] parts = tokenString.split("\\.");
        if (parts.length != 2) {
            return null;
        }

        String payloadBase64 = parts[0];
        String providedSignature = parts[1];

        String expectedSignature = computeHmacBase64(payloadBase64, secretKey);
        if (!MessageDigest.isEqual(providedSignature.getBytes(StandardCharsets.UTF_8),
                                  expectedSignature.getBytes(StandardCharsets.UTF_8))) {
            return null; // Invalid signature
        }

        try {
            byte[] jsonBytes = Base64.getUrlDecoder().decode(payloadBase64);
            String json = new String(jsonBytes, StandardCharsets.UTF_8);
            AuthToken token = JsonUtil.fromJson(json, AuthToken.class);
            if (token == null || token.isExpired()) {
                return null; // Expired or malformed
            }
            return token;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Signs an ExamTicket using HMAC-SHA256.
     */
    public static void signExamTicket(ExamTicket ticket, String secretKey) {
        if (ticket == null || secretKey == null) {
            throw new IllegalArgumentException("Ticket and secretKey cannot be null");
        }
        String signable = ticket.getSignableString();
        String signature = computeHmacBase64(signable, secretKey);
        ticket.setSignature(signature);
    }

    /**
     * Verifies an ExamTicket signature using HMAC-SHA256.
     */
    public static boolean verifyExamTicket(ExamTicket ticket, String secretKey) {
        if (ticket == null || ticket.getSignature() == null || secretKey == null) {
            return false;
        }
        String signable = ticket.getSignableString();
        String expectedSignature = computeHmacBase64(signable, secretKey);
        return MessageDigest.isEqual(ticket.getSignature().getBytes(StandardCharsets.UTF_8),
                                    expectedSignature.getBytes(StandardCharsets.UTF_8));
    }
}
