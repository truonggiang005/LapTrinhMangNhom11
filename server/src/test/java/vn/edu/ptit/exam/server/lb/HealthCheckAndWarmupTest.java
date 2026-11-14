package vn.edu.ptit.exam.server.lb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class HealthCheckAndWarmupTest {

    @Test
    void testHeartbeatTransitionDownAndUp() {
        BackendServerInfo server = new BackendServerInfo(2, "127.0.0.1", 9002, 2);
        assertEquals(ServerStatus.UNKNOWN, server.getStatus());

        // 1. Initial 1st and 2nd PONG: remains UNKNOWN
        server.recordPong(System.currentTimeMillis(), 10, System.currentTimeMillis());
        assertEquals(ServerStatus.UNKNOWN, server.getStatus());
        server.recordPong(System.currentTimeMillis(), 10, System.currentTimeMillis());
        assertEquals(ServerStatus.UNKNOWN, server.getStatus());

        // 3rd consecutive PONG -> transitions to UP
        server.recordPong(System.currentTimeMillis(), 10, System.currentTimeMillis());
        assertEquals(ServerStatus.UP, server.getStatus());
        assertTrue(server.getUpSinceTime() > 0);

        // 2. Failures: 1st and 2nd failure -> remains UP
        server.recordFailure();
        assertEquals(ServerStatus.UP, server.getStatus());
        server.recordFailure();
        assertEquals(ServerStatus.UP, server.getStatus());

        // 3rd consecutive failure -> transitions to DOWN
        server.recordFailure();
        assertEquals(ServerStatus.DOWN, server.getStatus());

        // 3. Recovery: 3 consecutive PONGs -> transitions back to UP
        server.recordPong(System.currentTimeMillis(), 10, System.currentTimeMillis());
        assertEquals(ServerStatus.DOWN, server.getStatus());
        server.recordPong(System.currentTimeMillis(), 10, System.currentTimeMillis());
        assertEquals(ServerStatus.DOWN, server.getStatus());
        server.recordPong(System.currentTimeMillis(), 10, System.currentTimeMillis());
        assertEquals(ServerStatus.UP, server.getStatus());
    }

    @Test
    void testWarmupWeightsRampUpOver30Seconds() {
        BackendServerInfo server = new BackendServerInfo(2, "127.0.0.1", 9002, 2);
        server.forceStatus(ServerStatus.UP);

        long now = System.currentTimeMillis();

        // Right after transition (0s uptime) -> small warmup weight (factor ~ 0.05 * baseWeight = 0.1)
        server.setUpSinceTime(now);
        double initialWeight = server.getEffectiveWeight();
        assertTrue(initialWeight < 0.5, "Initial effective weight right after UP should be low (warming up), was " + initialWeight);

        // At 15s (halfway) -> effectiveWeight ~ 2 * 0.5 = 1.0
        server.setUpSinceTime(now - 15_000L);
        double midWeight = server.getEffectiveWeight();
        assertTrue(midWeight >= 0.9 && midWeight <= 1.1, "Halfway effective weight should be around 1.0, was " + midWeight);

        // At 30s or more -> full baseWeight = 2.0
        server.setUpSinceTime(now - 35_000L);
        double fullWeight = server.getEffectiveWeight();
        assertEquals(2.0, fullWeight, 0.01, "Effective weight after 30s should equal full base weight 2.0");
    }

    @Test
    void testClockSkewCalculation() {
        BackendServerInfo server = new BackendServerInfo(3, "127.0.0.1", 9003, 2);
        long localTime = 1_000_000L;
        long remoteServerTime = 1_002_500L; // +2500ms skew

        server.recordPong(remoteServerTime, 20, localTime);
        assertEquals(2500L, server.getClockSkewMs(), "Clock skew should accurately calculate remoteTime - localTime");
    }
}
