package vn.edu.ptit.exam.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.edu.ptit.exam.common.config.Config;
import vn.edu.ptit.exam.common.dto.AuthToken;
import vn.edu.ptit.exam.common.dto.ExamItemDto;
import vn.edu.ptit.exam.common.dto.ExamListResponse;
import vn.edu.ptit.exam.common.dto.Message;
import vn.edu.ptit.exam.common.enums.ErrorCode;
import vn.edu.ptit.exam.common.enums.MessageType;
import vn.edu.ptit.exam.common.enums.UserRole;
import vn.edu.ptit.exam.common.util.HmacUtil;
import vn.edu.ptit.exam.common.util.JsonUtil;
import vn.edu.ptit.exam.server.dao.ExamDao;
import vn.edu.ptit.exam.server.handler.ListExamsHandler;
import vn.edu.ptit.exam.server.net.SocketContext;

import java.net.Socket;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ListExamsHandlerTest {

    private ExamDao mockExamDao;
    private ListExamsHandler listExamsHandler;
    private SocketContext dummyContext;
    private String validToken;
    private final String secretKey = Config.getInstance().getHmacSecretKey();

    @BeforeEach
    void setUp() {
        long now = System.currentTimeMillis();
        AuthToken token = new AuthToken("u-sv-001", "sv_01", "Nguyen Van A", UserRole.STUDENT, now, now + 4 * 3600 * 1000);
        validToken = HmacUtil.createToken(token, secretKey);

        mockExamDao = new ExamDao(null) {
            @Override
            public ExamListResponse listExamsForStudent(String studentId, String keyword, String statusFilter,
                                                        int page, int pageSize, long serverTime) throws SQLException {
                List<ExamItemDto> exams = new ArrayList<>();
                exams.add(new ExamItemDto("exam-001", "Thi LTM", "Lập trình mạng", 45, now - 10000, now + 50000, "OPEN", 20));
                exams.add(new ExamItemDto("exam-002", "Thi HDH", "Hệ điều hành", 60, now + 3600000, now + 7200000, "NOT_OPEN", 30));
                return new ExamListResponse(2, page, pageSize, exams);
            }
        };

        listExamsHandler = new ListExamsHandler(mockExamDao);
        dummyContext = new SocketContext("test-conn-2", new Socket());
    }

    @Test
    @DisplayName("Should return 401 UNAUTHENTICATED when token is missing or invalid")
    void testMissingOrInvalidToken() throws Exception {
        Message reqWithoutToken = Message.request(MessageType.LIST_EXAMS, "req-list-1");
        Message resp1 = listExamsHandler.handle(reqWithoutToken, dummyContext);
        assertEquals(ErrorCode.UNAUTHENTICATED.getCode(), resp1.getCode());

        Message reqWithBadToken = Message.request(MessageType.LIST_EXAMS, "req-list-2");
        reqWithBadToken.setToken("bad.token.signature");
        Message resp2 = listExamsHandler.handle(reqWithBadToken, dummyContext);
        assertEquals(ErrorCode.UNAUTHENTICATED.getCode(), resp2.getCode());
    }

    @Test
    @DisplayName("Should return 401 UNAUTHENTICATED when token is expired")
    void testExpiredToken() throws Exception {
        long now = System.currentTimeMillis();
        AuthToken expiredAuthToken = new AuthToken("u-sv-001", "sv_01", "Nguyen Van A", UserRole.STUDENT, now - 10000, now - 1000);
        String expiredTokenStr = HmacUtil.createToken(expiredAuthToken, secretKey);

        Message req = Message.request(MessageType.LIST_EXAMS, "req-list-expired");
        req.setToken(expiredTokenStr);

        Message resp = listExamsHandler.handle(req, dummyContext);
        assertEquals(ErrorCode.UNAUTHENTICATED.getCode(), resp.getCode());
    }

    @Test
    @DisplayName("Should return exam list when token is valid")
    void testValidTokenListExams() throws Exception {
        Message req = Message.request(MessageType.LIST_EXAMS, "req-list-3");
        req.setToken(validToken);
        req.setPayload(Map.of("page", 1, "pageSize", 10, "status", "OPEN"));

        Message resp = listExamsHandler.handle(req, dummyContext);

        assertEquals("OK", resp.getStatus());
        assertEquals(200, resp.getCode());
        assertNotNull(resp.getPayload());

        ExamListResponse listResp = JsonUtil.convertValue(resp.getPayload(), ExamListResponse.class);
        assertEquals(2, listResp.getTotal());
        assertEquals(1, listResp.getPage());
        assertEquals(10, listResp.getPageSize());
        assertEquals(2, listResp.getExams().size());
        assertEquals("OPEN", listResp.getExams().get(0).getStatus());
    }
}
