package vn.edu.ptit.exam.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import vn.edu.ptit.exam.common.enums.UserRole;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserDto {

    private String id;
    private String username;
    private String password;
    private String fullName;
    private String email;
    private UserRole role;
    private String className;
    private String status; // "ACTIVE", "LOCKED"
    private Integer version;

    public UserDto() {
    }

    public UserDto(String id, String username, String fullName, String email, UserRole role, String className, String status, Integer version) {
        this.id = id;
        this.username = username;
        this.fullName = fullName;
        this.email = email;
        this.role = role;
        this.className = className;
        this.status = status;
        this.version = version;
    }

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

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
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

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
}
