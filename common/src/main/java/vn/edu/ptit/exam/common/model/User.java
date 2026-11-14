package vn.edu.ptit.exam.common.model;

import vn.edu.ptit.exam.common.enums.UserRole;

import java.sql.Timestamp;

public class User {

    private String id;
    private String username;
    private String passwordHash;
    private String fullName;
    private String email;
    private UserRole role;
    private String className;
    private String status; // "ACTIVE", "LOCKED"
    private int failedLoginCount;
    private Timestamp lockedUntil;
    private int version;

    public User() {
    }

    public User(String id, String username, String passwordHash, String fullName, String email,
                UserRole role, String className, String status, int failedLoginCount, Timestamp lockedUntil, int version) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.email = email;
        this.role = role;
        this.className = className;
        this.status = status;
        this.failedLoginCount = failedLoginCount;
        this.lockedUntil = lockedUntil;
        this.version = version;
    }

    public boolean isLocked() {
        if ("LOCKED".equalsIgnoreCase(status)) {
            return true;
        }
        if (lockedUntil != null && lockedUntil.getTime() > System.currentTimeMillis()) {
            return true;
        }
        return false;
    }

    // Getters and Setters

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public UserRole getRole() {
        return role;
    }

    public void setRole(UserRole role) {
        this.role = role;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getFailedLoginCount() {
        return failedLoginCount;
    }

    public void setFailedLoginCount(int failedLoginCount) {
        this.failedLoginCount = failedLoginCount;
    }

    public Timestamp getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(Timestamp lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }
}
