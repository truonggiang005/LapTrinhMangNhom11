package vn.edu.ptit.exam.server.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.common.config.Config;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Manages the HikariCP connection pool for the business server.
 */
public class DatabaseManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);

    private final int serverIndex;
    private final HikariDataSource dataSource;

    public DatabaseManager(int serverIndex) {
        this.serverIndex = serverIndex;
        Config config = Config.getInstance();

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.getJdbcUrl(serverIndex));
        hikariConfig.setUsername(config.getDbUser());
        hikariConfig.setPassword(config.getDbPassword());
        hikariConfig.setMaximumPoolSize(config.getDbPoolMaxSize());
        hikariConfig.setMinimumIdle(config.getDbPoolMinIdle());
        hikariConfig.setConnectionTimeout(config.getDbPoolConnectionTimeoutMs());
        hikariConfig.setPoolName("HikariPool-Server-" + serverIndex);

        // Optimization properties for MySQL
        hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
        hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
        hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        hikariConfig.addDataSourceProperty("useServerPrepStmts", "true");

        log.info("Initializing HikariCP for Server {} with JDBC URL: {}", serverIndex, config.getJdbcUrl(serverIndex));
        this.dataSource = new HikariDataSource(hikariConfig);
        initTables();
    }

    private void initTables() {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS retake_requests (" +
                    "id VARCHAR(36) PRIMARY KEY, " +
                    "exam_id VARCHAR(36) NOT NULL, " +
                    "student_id VARCHAR(36) NOT NULL, " +
                    "student_code VARCHAR(50) NOT NULL, " +
                    "student_name VARCHAR(100), " +
                    "reason TEXT, " +
                    "status VARCHAR(20) NOT NULL DEFAULT 'PENDING', " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, " +
                    "INDEX idx_retake_exam (exam_id), " +
                    "INDEX idx_retake_student (student_id), " +
                    "INDEX idx_retake_status (status)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        } catch (Exception e) {
            log.error("Failed to auto-create retake_requests table on Server {}: {}", serverIndex, e.getMessage());
        }
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public HikariDataSource getDataSource() {
        return dataSource;
    }

    public int getServerIndex() {
        return serverIndex;
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            log.info("Closing HikariCP for Server {}", serverIndex);
            dataSource.close();
        }
    }
}
