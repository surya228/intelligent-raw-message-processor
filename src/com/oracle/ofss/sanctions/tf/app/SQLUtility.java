package com.oracle.ofss.sanctions.tf.app;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.sql.Connection;
import java.util.Properties;

public class SQLUtility {
    private static final Logger logger = LoggerFactory.getLogger(SQLUtility.class);
    private static HikariDataSource dataSource;

    static {
        try {
            Properties props = new Properties();
            try (FileReader reader = new FileReader(Constants.COMMON_CONFIG_FILE_PATH)) {
                props.load(reader);
            }

            String jdbcUrl = props.getProperty(Constants.JDBC_URL);
            String jdbcDriver = props.getProperty(Constants.JDBC_DRIVER);
            String walletname = props.getProperty(Constants.WALLET_NAME);
            String tnsAdminPath = Constants.PARENT_DIRECTORY + File.separator + Constants.BIN_FOLDER_NAME + File.separator + walletname;

            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(jdbcUrl);
            config.setDriverClassName(jdbcDriver);
            config.addDataSourceProperty(Constants.TNS_ADMIN, tnsAdminPath);

            // Optimized connection pool settings for bulk operations
            config.setMaximumPoolSize(20); // Increased for better throughput
            config.setMinimumIdle(10);
            config.setConnectionTimeout(900000000);
            config.setIdleTimeout(900000000);
            config.setMaxLifetime(1800000000);
            config.setLeakDetectionThreshold(60000);

            // Performance optimizations and timeout settings
            config.addDataSourceProperty("oracle.jdbc.ReadTimeout", "60000");
            config.addDataSourceProperty("oracle.net.CONNECT_TIMEOUT", "10000");
            config.addDataSourceProperty("oracle.net.authenticationTimeout", "120000"); // 120 seconds for authentication
            config.addDataSourceProperty("oracle.jdbc.defaultNChar", "true");

            dataSource = new HikariDataSource(config);
        } catch (Exception e) {
            logger.error("Error initializing connection pool: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public static Connection getDbConnection() throws Exception {
        int maxRetries = 3;
        long retryDelayMs = 5000; // 5 seconds

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                Connection connection = dataSource.getConnection();
                logger.info(Constants.CONNECTION_ESTABLISHED);
                return connection;
            } catch (Exception e) {
                if (attempt == maxRetries) {
                    logger.error("Failed to establish database connection after {} attempts", maxRetries);
                    throw e;
                }
                logger.warn("Database connection attempt {} failed: {}. Retrying in {} ms...", attempt, e.getMessage(), retryDelayMs);
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while waiting to retry connection", ie);
                }
            }
        }
        // This should never be reached
        throw new RuntimeException("Unexpected error in connection retry logic");
    }
}
