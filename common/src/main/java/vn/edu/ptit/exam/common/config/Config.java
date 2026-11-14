package vn.edu.ptit.exam.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

/**
 * System configuration reader loading settings from application.properties.
 */
public class Config {

    private static final Logger log = LoggerFactory.getLogger(Config.class);
    private static final String DEFAULT_PROPERTIES_FILE = "application.properties";

    private static final Config INSTANCE = new Config();

    private final Properties properties = new Properties();

    private Config() {
        loadDefault();
    }

    public static Config getInstance() {
        return INSTANCE;
    }

    private void loadDefault() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(DEFAULT_PROPERTIES_FILE)) {
            if (in != null) {
                properties.load(in);
                log.info("Loaded configuration from classpath: {}", DEFAULT_PROPERTIES_FILE);
            } else {
                log.warn("Default properties file {} not found on classpath", DEFAULT_PROPERTIES_FILE);
            }
        } catch (Exception e) {
            log.error("Failed to load default properties: {}", e.getMessage(), e);
        }
    }

    public void loadFromFile(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            log.warn("Properties file does not exist: {}", filePath);
            return;
        }
        try (InputStream in = new FileInputStream(file)) {
            properties.load(in);
            log.info("Overlaid configuration from external file: {}", filePath);
        } catch (Exception e) {
            log.error("Failed to load properties from {}: {}", filePath, e.getMessage(), e);
        }
    }

    // --- Property Accessors ---

    public String getProperty(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    public int getIntProperty(String key, int defaultValue) {
        String val = properties.getProperty(key);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public long getLongProperty(String key, long defaultValue) {
        String val = properties.getProperty(key);
        if (val == null) return defaultValue;
        try {
            return Long.parseLong(val.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean getBooleanProperty(String key, boolean defaultValue) {
        String val = properties.getProperty(key);
        if (val == null) return defaultValue;
        return Boolean.parseBoolean(val.trim());
    }

    // --- Load Balancer Config ---

    public String getLbHost() {
        return getProperty("lb.host", "127.0.0.1");
    }

    public int getLbPort() {
        return getIntProperty("lb.port", 9000);
    }

    public int getLbMaxConnections() {
        return getIntProperty("lb.maxConnections", 500);
    }

    public int getLbMaxParallelStartExam() {
        return getIntProperty("lb.maxParallelStartExam", 50);
    }

    public long getLbQueueCheckIntervalMs() {
        return getLongProperty("lb.queueCheckIntervalMs", 3000);
    }

    // --- Server Specific Config (N = 1, 2, 3, 4) ---

    public String getServerId(int serverIndex) {
        return getProperty("server." + serverIndex + ".id", "SERVER_" + serverIndex);
    }

    public String getServerHost(int serverIndex) {
        return getProperty("server." + serverIndex + ".host", "127.0.0.1");
    }

    public int getServerPort(int serverIndex) {
        return getIntProperty("server." + serverIndex + ".port", 9000 + serverIndex);
    }

    public int getServerSyncPort(int serverIndex) {
        return getIntProperty("server." + serverIndex + ".syncPort", 9100 + serverIndex);
    }

    public String getServerDbName(int serverIndex) {
        return getProperty("server." + serverIndex + ".db.name", "exam_db_" + serverIndex);
    }

    public int getServerWeight(int serverIndex) {
        int defaultWeight = serverIndex == 1 ? 1 : 2;
        return getIntProperty("server." + serverIndex + ".weight", defaultWeight);
    }

    // --- Database General Config ---

    public String getDbHost() {
        return getProperty("db.host", "127.0.0.1");
    }

    public int getDbPort() {
        return getIntProperty("db.port", 3306);
    }

    public String getDbUser() {
        return getProperty("db.user", "root");
    }

    public String getDbPassword() {
        return getProperty("db.password", "root");
    }

    public int getDbPoolMaxSize() {
        return getIntProperty("db.pool.maxSize", 20);
    }

    public int getDbPoolMinIdle() {
        return getIntProperty("db.pool.minIdle", 5);
    }

    public long getDbPoolConnectionTimeoutMs() {
        return getLongProperty("db.pool.connectionTimeoutMs", 30000);
    }

    public String getJdbcUrl(int serverIndex) {
        String dbName = getServerDbName(serverIndex);
        return String.format("jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Ho_Chi_Minh&characterEncoding=UTF-8",
                getDbHost(), getDbPort(), dbName);
    }

    // --- Security Config ---

    public String getHmacSecretKey() {
        return getProperty("security.hmac.secretKey", "Pt1t_Ex@m_Syst3m_Sup3r_S3cr3t_K3y_2026_De13_Nhom11");
    }

    public int getTokenExpiryHours() {
        return getIntProperty("security.token.expiryHours", 4);
    }

    public long getTokenExpiryMillis() {
        return getTokenExpiryHours() * 3600L * 1000L;
    }

    // --- Operational Parameters (Section 1.3) ---

    public long getHeartbeatIntervalMs() {
        return getLongProperty("heartbeat.intervalMs", 1000);
    }

    public int getHeartbeatFailThreshold() {
        return getIntProperty("heartbeat.failThreshold", 3);
    }

    public int getHeartbeatRecoverThreshold() {
        return getIntProperty("heartbeat.recoverThreshold", 3);
    }

    public int getHeartbeatWarmupSeconds() {
        return getIntProperty("heartbeat.warmupSeconds", 30);
    }

    public long getHeartbeatClockSkewWarnThresholdMs() {
        return getLongProperty("heartbeat.clockSkewWarnThresholdMs", 1000);
    }

    public long getClientAutosaveIntervalMs() {
        return getLongProperty("client.autosaveIntervalMs", 20000);
    }

    public long getClientResponseTimeoutMs() {
        return getLongProperty("client.responseTimeoutMs", 10000);
    }

    public int[] getClientReconnectDelaysSec() {
        String delays = getProperty("client.reconnectDelaysSec", "1,2,4,8,16,30");
        String[] parts = delays.split(",");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Integer.parseInt(parts[i].trim());
        }
        return result;
    }

    public long getClientPingIntervalMs() {
        return getLongProperty("client.pingIntervalMs", 5000);
    }

    public long getExamLateToleranceMs() {
        return getLongProperty("exam.lateToleranceMs", 5000);
    }

    public long getExamScanExpiredIntervalMs() {
        return getLongProperty("exam.scanExpiredIntervalMs", 10000);
    }

    public int getSyncBatchSize() {
        return getIntProperty("sync.batchSize", 100);
    }

    public long getSyncBatchIntervalMs() {
        return getLongProperty("sync.batchIntervalMs", 200);
    }

    public long getSyncConsistencyCheckIntervalMs() {
        return getLongProperty("sync.consistencyCheckIntervalMs", 300000);
    }

    public long getSyncAckTimeoutMs() {
        return getLongProperty("sync.ackTimeoutMs", 3000);
    }
}
