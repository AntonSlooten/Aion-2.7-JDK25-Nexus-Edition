package com.aionemu.commons.database;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Locale;

import com.aionemu.commons.configs.DatabaseConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central database connection factory.
 *
 * <p>Modernized for Java 25 with HikariCP while keeping the old Aion/BoneCP
 * configuration keys compatible.</p>
 */
public final class DatabaseFactory {

	private static final Logger log = LoggerFactory.getLogger(DatabaseFactory.class);

	private static volatile HikariDataSource dataSource;
	private static String databaseName;
	private static int databaseMajorVersion;
	private static int databaseMinorVersion;

	private DatabaseFactory() {
	}

	public static synchronized void init() {
		if (dataSource != null) {
			return;
		}

		try {
			Class.forName(DatabaseConfig.DATABASE_DRIVER.getName());
		} catch (Exception e) {
			log.error("Error obtaining DB driver", e);
			throw new IllegalStateException("DB Driver does not exist: " + DatabaseConfig.DATABASE_DRIVER, e);
		}

		HikariConfig config = createHikariConfig();

		try {
			dataSource = new HikariDataSource(config);
			loadDatabaseMetadata();
		} catch (Exception e) {
			log.error("Error while creating DB connection pool", e);
			shutdown();
			throw new IllegalStateException("DatabaseFactory not initialized!", e);
		}

		log.info("Successfully connected to database: {} {}.{} | pool minIdle={} maxPoolSize={}", databaseName,
			databaseMajorVersion, databaseMinorVersion, config.getMinimumIdle(), config.getMaximumPoolSize());
	}

	private static HikariConfig createHikariConfig() {
		int maximumPoolSize = Math.max(1,
			DatabaseConfig.DATABASE_POOL_PARTITION_COUNT * DatabaseConfig.DATABASE_POOL_CONNECTIONS_MAX);
		int minimumIdle = Math.max(0,
			DatabaseConfig.DATABASE_POOL_PARTITION_COUNT * DatabaseConfig.DATABASE_POOL_CONNECTIONS_MIN);

		if (minimumIdle > maximumPoolSize) {
			log.warn("Database minimum pool size {} is greater than maximum pool size {}. Adjusting minimum idle.",
				minimumIdle, maximumPoolSize);
			minimumIdle = maximumPoolSize;
		}

		HikariConfig config = new HikariConfig();
		config.setPoolName("AionHikariPool");
		config.setJdbcUrl(DatabaseConfig.DATABASE_URL);
		config.setUsername(DatabaseConfig.DATABASE_USER);
		config.setPassword(DatabaseConfig.DATABASE_PASSWORD);
		config.setDriverClassName(DatabaseConfig.DATABASE_DRIVER.getName());
		config.setMinimumIdle(minimumIdle);
		config.setMaximumPoolSize(maximumPoolSize);
		config.setAutoCommit(true);
		config.setConnectionTimeout(Math.max(250, DatabaseConfig.DATABASE_HIKARI_CONNECTION_TIMEOUT));
		config.setValidationTimeout(Math.max(250, DatabaseConfig.DATABASE_HIKARI_VALIDATION_TIMEOUT));
		config.setIdleTimeout(Math.max(10_000, DatabaseConfig.DATABASE_HIKARI_IDLE_TIMEOUT));
		config.setMaxLifetime(Math.max(30_000, DatabaseConfig.DATABASE_HIKARI_MAX_LIFETIME));

		if (DatabaseConfig.DATABASE_HIKARI_LEAK_DETECTION_THRESHOLD > 0) {
			config.setLeakDetectionThreshold(Math.max(2_000, DatabaseConfig.DATABASE_HIKARI_LEAK_DETECTION_THRESHOLD));
		}

		applyDriverOptimizations(config);
		return config;
	}

	private static void applyDriverOptimizations(HikariConfig config) {
		String driverName = DatabaseConfig.DATABASE_DRIVER.getName().toLowerCase(Locale.ROOT);
		String jdbcUrl = DatabaseConfig.DATABASE_URL.toLowerCase(Locale.ROOT);

		if (driverName.contains("mysql") || jdbcUrl.startsWith("jdbc:mysql:")) {
			config.addDataSourceProperty("cachePrepStmts", "true");
			config.addDataSourceProperty("prepStmtCacheSize", "250");
			config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
			config.addDataSourceProperty("useServerPrepStmts", "true");
			config.addDataSourceProperty("useLocalSessionState", "true");
			config.addDataSourceProperty("rewriteBatchedStatements", "true");
			config.addDataSourceProperty("cacheResultSetMetadata", "true");
			config.addDataSourceProperty("cacheServerConfiguration", "true");
			config.addDataSourceProperty("elideSetAutoCommits", "true");
			config.addDataSourceProperty("maintainTimeStats", "false");
		}
	}

	private static void loadDatabaseMetadata() throws SQLException {
		try (Connection connection = getConnection()) {
			DatabaseMetaData metadata = connection.getMetaData();
			databaseName = metadata.getDatabaseProductName();
			databaseMajorVersion = metadata.getDatabaseMajorVersion();
			databaseMinorVersion = metadata.getDatabaseMinorVersion();
		}
	}

	public static Connection getConnection() throws SQLException {
		HikariDataSource source = dataSource;
		if (source == null) {
			throw new SQLException("DatabaseFactory is not initialized.");
		}

		Connection connection = source.getConnection();
		if (!connection.getAutoCommit()) {
			log.warn("Connection obtained with auto-commit disabled. Forcing auto-commit to true.");
			connection.setAutoCommit(true);
		}
		return connection;
	}

	public static int getActiveConnections() {
		HikariDataSource source = dataSource;
		return source == null ? 0 : source.getHikariPoolMXBean().getActiveConnections();
	}

	public static int getIdleConnections() {
		HikariDataSource source = dataSource;
		return source == null ? 0 : source.getHikariPoolMXBean().getIdleConnections();
	}

	public static synchronized void shutdown() {
		HikariDataSource source = dataSource;
		if (source != null) {
			try {
				source.close();
			} catch (Exception e) {
				log.warn("Failed to shutdown DatabaseFactory", e);
			} finally {
				dataSource = null;
			}
		}
	}

	public static void close(PreparedStatement statement, Connection connection) {
		close(statement);
		close(connection);
	}

	public static void close(PreparedStatement statement) {
		if (statement == null) {
			return;
		}

		try {
			if (!statement.isClosed()) {
				statement.close();
			}
		} catch (SQLException e) {
			log.error("Can't close PreparedStatement", e);
		}
	}

	public static void close(Connection connection) {
		if (connection == null) {
			return;
		}

		try {
			if (!connection.getAutoCommit()) {
				connection.setAutoCommit(true);
			}
		} catch (SQLException e) {
			log.error("Failed to reset auto-commit while closing connection", e);
		}

		try {
			connection.close();
		} catch (SQLException e) {
			log.error("Failed to close database connection", e);
		}
	}

	public static String getDatabaseName() {
		return databaseName;
	}

	public static int getDatabaseMajorVersion() {
		return databaseMajorVersion;
	}

	public static int getDatabaseMinorVersion() {
		return databaseMinorVersion;
	}
}
