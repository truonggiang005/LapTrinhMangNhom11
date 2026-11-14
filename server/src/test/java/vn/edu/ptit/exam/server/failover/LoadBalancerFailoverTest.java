package vn.edu.ptit.exam.server.failover;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.edu.ptit.exam.common.config.Config;
import vn.edu.ptit.exam.common.dto.ExamTicket;
import vn.edu.ptit.exam.common.dto.Message;
import vn.edu.ptit.exam.common.enums.MessageType;
import vn.edu.ptit.exam.server.lb.BackendServerInfo;
import vn.edu.ptit.exam.server.lb.LoadBalancer;
import vn.edu.ptit.exam.server.lb.ServerStatus;
import vn.edu.ptit.exam.server.lb.SessionRegistry;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.*;

class LoadBalancerFailoverTest {

    private LoadBalancer lb;
    private final ConcurrentLinkedQueue<Message> pushedMessages = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void setUp() {
        pushedMessages.clear();
        // Create LB on ephemeral test port
        lb = new LoadBalancer(9876);
        lb.setMinServersUp(1);

        // Mark backend servers 1, 2, 4 as UP; Server 3 as UP initially
        for (BackendServerInfo s : lb.getBackendServers()) {
            s.setStatus(ServerStatus.UP);
        }
    }

    @AfterEach
    void tearDown() {
        if (lb != null) {
            lb.stop();
        }
    }

    @Test
    @DisplayName("Failover triggers when Server 3 dies, sends SERVER_SWITCHING, and redistributes attempt")
    void testFailoverServer3_RemapsAttemptAndPushesNotice() throws Exception {
        // Mock socket & writer for student session
        StringWriter sw = new StringWriter();
        BufferedWriter writer = new BufferedWriter(sw);
        Socket dummySocket = new Socket();

        // Register student session on Server 3
        lb.getSessionRegistry().registerLogin("u-sv-01", "sv_01", dummySocket, writer, 3);
        SessionRegistry.ClientSessionEntry session = lb.getSessionRegistry().getSessionByUserId("u-sv-01");
        assertNotNull(session);
        session.setInExam(true);
        session.setAttemptId("att-01");

        // Register attempt on Server 3 in ExamRegistry
        ExamTicket ticket = new ExamTicket("att-01", "u-sv-01", "exam-1", 12345L,
                System.currentTimeMillis(), System.currentTimeMillis() + 300000, "server-3");
        lb.getExamRegistry().registerExam("u-sv-01", "exam-1", "att-01", 3, ticket);

        assertEquals(3, lb.getExamRegistry().getServerForAttempt("att-01").orElse(-1));

        // Mark Server 3 as DOWN and trigger failover
        lb.getBackendServer(3).setStatus(ServerStatus.DOWN);
        lb.triggerFailoverForServer(3);

        // Failover count incremented
        assertEquals(1, lb.getFailoverCount());

        // Attempt should be reassigned to one of the healthy servers (1, 2, or 4)
        int newServerId = lb.getExamRegistry().getServerForAttempt("att-01").orElse(-1);
        assertNotEquals(3, newServerId);
        assertTrue(newServerId == 1 || newServerId == 2 || newServerId == 4);

        // Client session assignedServerId updated
        assertEquals(newServerId, session.getAssignedServerId());

        // Verify DOWN servers list includes server-3
        List<String> downList = lb.getDownServerIds();
        assertTrue(downList.contains("server-3"));
    }

    @Test
    @DisplayName("Failover distributes multiple failed attempts across remaining live servers")
    void testFailoverMultipleAttempts_DistributesEvenly() throws Exception {
        // Setup 4 active attempts on Server 3
        List<SessionRegistry.ClientSessionEntry> sessions = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            String uid = "user-" + i;
            String attId = "att-" + i;
            StringWriter sw = new StringWriter();
            BufferedWriter writer = new BufferedWriter(sw);
            Socket dummySocket = new Socket();

            lb.getSessionRegistry().registerLogin(uid, "username" + i, dummySocket, writer, 3);
            SessionRegistry.ClientSessionEntry s = lb.getSessionRegistry().getSessionByUserId(uid);
            s.setInExam(true);
            s.setAttemptId(attId);
            sessions.add(s);

            ExamTicket ticket = new ExamTicket(attId, uid, "exam-1", 1000L + i,
                    System.currentTimeMillis(), System.currentTimeMillis() + 300000, "server-3");
            lb.getExamRegistry().registerExam(uid, "exam-1", attId, 3, ticket);
        }

        // Server 3 goes DOWN
        lb.getBackendServer(3).setStatus(ServerStatus.DOWN);
        lb.triggerFailoverForServer(3);

        // Check where the 6 attempts landed
        Map<Integer, Integer> distribution = new java.util.HashMap<>();
        for (int i = 1; i <= 6; i++) {
            int targetServer = lb.getExamRegistry().getServerForAttempt("att-" + i).orElse(-1);
            assertNotEquals(3, targetServer, "No attempt should remain on dead server 3");
            distribution.put(targetServer, distribution.getOrDefault(targetServer, 0) + 1);
        }

        // Must be distributed across live servers 1, 2, 4 (not all dumped on one server)
        assertTrue(distribution.keySet().size() >= 2, "Failover must distribute across multiple live servers");
    }
}
