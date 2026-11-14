package vn.edu.ptit.exam.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.edu.ptit.exam.common.config.Config;

import static org.junit.jupiter.api.Assertions.*;

class ConfigTest {

    @Test
    @DisplayName("Should load default configuration parameters from application.properties")
    void testDefaultConfig() {
        Config config = Config.getInstance();
        assertNotNull(config);

        // Load Balancer
        assertEquals("127.0.0.1", config.getLbHost());
        assertEquals(9000, config.getLbPort());
        assertEquals(500, config.getLbMaxConnections());
        assertEquals(50, config.getLbMaxParallelStartExam());

        // Server ports
        assertEquals(9001, config.getServerPort(1));
        assertEquals(9002, config.getServerPort(2));
        assertEquals(9003, config.getServerPort(3));
        assertEquals(9004, config.getServerPort(4));

        // Sync ports
        assertEquals(9101, config.getServerSyncPort(1));
        assertEquals(9102, config.getServerSyncPort(2));
        assertEquals(9103, config.getServerSyncPort(3));
        assertEquals(9104, config.getServerSyncPort(4));

        // Weights
        assertEquals(1, config.getServerWeight(1));
        assertEquals(2, config.getServerWeight(2));
        assertEquals(2, config.getServerWeight(3));
        assertEquals(2, config.getServerWeight(4));

        // Database
        assertEquals(3306, config.getDbPort());
        assertEquals("exam_db_1", config.getServerDbName(1));
        assertEquals("exam_db_2", config.getServerDbName(2));
        assertTrue(config.getJdbcUrl(1).contains("exam_db_1"));

        // Operational parameters
        assertEquals(1000, config.getHeartbeatIntervalMs());
        assertEquals(3, config.getHeartbeatFailThreshold());
        assertEquals(3, config.getHeartbeatRecoverThreshold());
        assertEquals(30, config.getHeartbeatWarmupSeconds());
        assertEquals(20000, config.getClientAutosaveIntervalMs());
        assertEquals(10000, config.getClientResponseTimeoutMs());
        assertArrayEquals(new int[]{1, 2, 4, 8, 16, 30}, config.getClientReconnectDelaysSec());
        assertEquals(5000, config.getExamLateToleranceMs());
        assertEquals(10000, config.getExamScanExpiredIntervalMs());
        assertEquals(100, config.getSyncBatchSize());
        assertEquals(200, config.getSyncBatchIntervalMs());
        assertEquals(300000, config.getSyncConsistencyCheckIntervalMs());
    }
}
