package vn.edu.ptit.exam.server.lb;

import org.junit.jupiter.api.Test;
import vn.edu.ptit.exam.common.enums.ErrorCode;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.*;

public class DualLoginTest {

    private static class DummySocket extends Socket {
        private boolean closed = false;

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public boolean isConnected() {
            return !closed;
        }

        @Override
        public synchronized void close() {
            this.closed = true;
        }
    }

    @Test
    void testDualLoginInExamRejectedWith409() throws Exception {
        SessionRegistry registry = new SessionRegistry();

        DummySocket socket1 = new DummySocket();
        BufferedWriter writer1 = new BufferedWriter(new StringWriter());

        // 1. First login succeeds
        registry.registerLogin("sv01", "student01", socket1, writer1, 2);
        assertNotNull(registry.getSessionByUserId("sv01"));

        // 2. Student enters exam
        registry.setExamActive("sv01", true);
        assertTrue(registry.getSessionByUserId("sv01").isInExam());

        // 3. Second login attempt while in exam -> must be rejected with SESSION_IN_EXAM (409)
        DummySocket socket2 = new DummySocket();
        BufferedWriter writer2 = new BufferedWriter(new StringWriter());

        SessionRegistry.SessionException ex = assertThrows(SessionRegistry.SessionException.class, () ->
                registry.registerLogin("sv01", "student01", socket2, writer2, 3)
        );

        assertEquals(ErrorCode.SESSION_IN_EXAM, ex.getErrorCode());
        assertFalse(socket1.isClosed(), "Original socket in exam must NOT be closed");
    }

    @Test
    void testDualLoginNotInExamReplacesSession() throws Exception {
        SessionRegistry registry = new SessionRegistry();

        DummySocket socket1 = new DummySocket();
        StringWriter sw1 = new StringWriter();
        BufferedWriter writer1 = new BufferedWriter(sw1);

        // 1. First login succeeds (not in exam)
        registry.registerLogin("sv02", "student02", socket1, writer1, 2);
        assertFalse(registry.getSessionByUserId("sv02").isInExam());

        // 2. Second login attempt while NOT in exam -> replaces session and closes socket 1
        DummySocket socket2 = new DummySocket();
        BufferedWriter writer2 = new BufferedWriter(new StringWriter());

        registry.registerLogin("sv02", "student02", socket2, writer2, 3);

        // Socket 1 should have been sent SESSION_REPLACED and closed
        assertTrue(socket1.isClosed(), "Original socket should be closed upon replacement");
        assertTrue(sw1.toString().contains("SESSION_REPLACED"), "SESSION_REPLACED push message must be sent to old socket");

        // Active session now points to socket 2 and server 3
        SessionRegistry.ClientSessionEntry current = registry.getSessionByUserId("sv02");
        assertNotNull(current);
        assertEquals(socket2, current.getClientSocket());
        assertEquals(3, current.getAssignedServerId());
    }
}
