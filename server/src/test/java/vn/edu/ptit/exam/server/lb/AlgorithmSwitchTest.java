package vn.edu.ptit.exam.server.lb;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class AlgorithmSwitchTest {

    @Test
    void testWeightedLeastConnectionsSelection() {
        ServerSelector selector = new ServerSelector();
        selector.setAlgorithm(AlgorithmType.WEIGHTED_LEAST_CONNECTIONS);

        BackendServerInfo s1 = new BackendServerInfo(1, "127.0.0.1", 9001, 1);
        BackendServerInfo s2 = new BackendServerInfo(2, "127.0.0.1", 9002, 2);
        BackendServerInfo s3 = new BackendServerInfo(3, "127.0.0.1", 9003, 2);

        s1.forceStatus(ServerStatus.UP);
        s2.forceStatus(ServerStatus.UP);
        s3.forceStatus(ServerStatus.UP);

        long past = System.currentTimeMillis() - 35_000L;
        s1.setUpSinceTime(past);
        s2.setUpSinceTime(past);
        s3.setUpSinceTime(past);

        // s1: 0 conns / weight 1 = 0
        // s2: 4 conns / weight 2 = 2.0
        // s3: 6 conns / weight 2 = 3.0
        for (int i = 0; i < 4; i++) s2.incrementConnections();
        for (int i = 0; i < 6; i++) s3.incrementConnections();

        // Best is s1
        Optional<BackendServerInfo> selected = selector.selectServer(List.of(s1, s2, s3));
        assertTrue(selected.isPresent());
        assertEquals(1, selected.get().getServerId());

        // Now s1 gets 5 connections (ratio = 5.0). Best becomes s2 (ratio = 2.0)
        for (int i = 0; i < 5; i++) s1.incrementConnections();
        selected = selector.selectServer(List.of(s1, s2, s3));
        assertTrue(selected.isPresent());
        assertEquals(2, selected.get().getServerId());
    }

    @Test
    void testHotSwapToRoundRobin() {
        ServerSelector selector = new ServerSelector();
        assertEquals(AlgorithmType.WEIGHTED_LEAST_CONNECTIONS, selector.getAlgorithm());

        // Hot swap to ROUND_ROBIN
        selector.setAlgorithm(AlgorithmType.ROUND_ROBIN);
        assertEquals(AlgorithmType.ROUND_ROBIN, selector.getAlgorithm());

        BackendServerInfo s1 = new BackendServerInfo(1, "127.0.0.1", 9001, 1);
        BackendServerInfo s2 = new BackendServerInfo(2, "127.0.0.1", 9002, 2);
        s1.forceStatus(ServerStatus.UP);
        s2.forceStatus(ServerStatus.UP);

        // Bypass 30s warm-up so full base weights 1 and 2 take effect
        long past = System.currentTimeMillis() - 35_000L;
        s1.setUpSinceTime(past);
        s2.setUpSinceTime(past);

        // In weighted round robin with weights 1 and 2, out of 3 calls, s2 gets 2 and s1 gets 1
        int countS1 = 0;
        int countS2 = 0;
        for (int i = 0; i < 6; i++) {
            BackendServerInfo s = selector.selectServer(List.of(s1, s2)).get();
            if (s.getServerId() == 1) countS1++;
            else if (s.getServerId() == 2) countS2++;
        }

        assertEquals(2, countS1, "Server 1 (weight 1) should get 2 requests out of 6");
        assertEquals(4, countS2, "Server 2 (weight 2) should get 4 requests out of 6");
    }

    @Test
    void testNoServerAvailableWhenAllDown() {
        ServerSelector selector = new ServerSelector();

        BackendServerInfo s1 = new BackendServerInfo(1, "127.0.0.1", 9001, 1);
        BackendServerInfo s2 = new BackendServerInfo(2, "127.0.0.1", 9002, 2);
        s1.forceStatus(ServerStatus.DOWN);
        s2.forceStatus(ServerStatus.DOWN);

        Optional<BackendServerInfo> selected = selector.selectServer(List.of(s1, s2));
        assertFalse(selected.isPresent(), "Must return empty when no UP servers are available");
    }
}
