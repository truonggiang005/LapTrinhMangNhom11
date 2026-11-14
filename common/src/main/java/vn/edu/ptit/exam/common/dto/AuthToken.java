package vn.edu.ptit.exam.common.dto;

import vn.edu.ptit.exam.common.enums.UserRole;

/**
 * Representation of the authentication token payload.
 */
public class AuthToken {

    private String userId;
    private String username;
    private String fullName;
    private UserRole role;
    private long issuedAt;
    private long expiresAt;

    public AuthToken() {
    }

    public AuthToken(String userId, String username, String fullName, UserRole role, long issuedAt, long expiresAt) {
        this.userId = userId;
        this.username = username;
        this.fullName = fullName;
        this.role = role;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > expiresAt;
    }

    public boolean isExpired(long currentTimeMillis) {
        return currentTimeMillis > expiresAt;
    }

    // Getters and Setters

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public UserRole getRole() {
        return role;
    }

    public void setRole(UserRole role) {
        this.role = role;
    }

    public long getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(long issuedAt) {
        this.issuedAt = issuedAt;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(long expiresAt) {
        this.expiresAt = expiresAt;
    }
}
