package vn.edu.ptit.exam.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserListResponse {

    private List<UserDto> users;
    private int totalRows;
    private int page;
    private int pageSize;

    public UserListResponse() {
    }

    public UserListResponse(List<UserDto> users, int totalRows, int page, int pageSize) {
        this.users = users;
        this.totalRows = totalRows;
        this.page = page;
        this.pageSize = pageSize;
    }

    public List<UserDto> getUsers() {
        return users;
    }

    public void setUsers(List<UserDto> users) {
        this.users = users;
    }

    public int getTotalRows() {
        return totalRows;
    }

    public void setTotalRows(int totalRows) {
        this.totalRows = totalRows;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }
}
