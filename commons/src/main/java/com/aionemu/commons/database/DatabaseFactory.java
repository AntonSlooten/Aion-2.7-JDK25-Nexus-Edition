/*
 * This file is part of aion-emu <aion-emu.com>.
 *
 * aion-emu is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * aion-emu is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with aion-emu.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aionemu.commons.database;

import com.aionemu.commons.configs.DatabaseConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * <b>Database Factory</b><br>
 * <br>
 * This file is used for creating a pool of connections for the server.<br>
 * It utilizes database.properties and creates a pool of connections and automatically recycles them when closed.<br>
 * Modernized to use HikariCP for Java 25 compatibility.
 *
 * @author Disturbing
 * @author SoulKeeper
 * @author Nexus Connect (HikariCP Update)
 */
public class DatabaseFactory {

	private static final Logger log = LoggerFactory.getLogger(DatabaseFactory.class);

	private static HikariDataSource connectionPool;

	private static String databaseName;
	private static int databaseMajorVersion;
	private static int databaseMinorVersion;

	public synchronized static void init() {
		if (connectionPool != null) {
			return;
		}

		try {
			DatabaseConfig.DATABASE_DRIVER.getDeclaredConstructor().newInstance();
		}
		catch (Exception e) {
			log.error("Error obtaining DB driver", e);
			throw new Error("DB Driver doesnt exist!");
		}

		HikariConfig config = new HikariConfig();
		config.setJdbcUrl(DatabaseConfig.DATABASE_URL);
		config.setUsername(DatabaseConfig.DATABASE_USER);
		config.setPassword(DatabaseConfig.DATABASE_PASSWORD);

		// Menyesuaikan konfigurasi HikariCP agar mirip dengan pengaturan pool lama
		config.setMinimumIdle(DatabaseConfig.DATABASE_BONECP_PARTITION_CONNECTIONS_MIN);
		config.setMaximumPoolSize(DatabaseConfig.DATABASE_BONECP_PARTITION_CONNECTIONS_MAX);
		config.setConnectionTimeout(30000); // 30 detik
		config.setIdleTimeout(600000); // 10 menit
		config.setMaxLifetime(1800000); // 30 menit
		config.setAutoCommit(true);

		try {
			connectionPool = new HikariDataSource(config);
		} catch (Exception e) {
			log.error("Error while creating DB Connection pool", e);
			throw new Error("DatabaseFactory not initialized!", e);
		}

		try (Connection c = getConnection()) {
			DatabaseMetaData dmd = c.getMetaData();
			databaseName = dmd.getDatabaseProductName();
			databaseMajorVersion = dmd.getDatabaseMajorVersion();
			databaseMinorVersion = dmd.getDatabaseMinorVersion();
		}
		catch (Exception e) {
			log.error("Error with connection string: " + DatabaseConfig.DATABASE_URL, e);
			throw new Error("DatabaseFactory not initialized!");
		}

		log.info("Successfully connected to database with HikariCP");
	}

	public static Connection getConnection() throws SQLException {
		Connection con = connectionPool.getConnection();

		if(!con.getAutoCommit()){
			log.error("Connection Settings Error: Connection obtained from database factory should be in auto-commit mode.");
			con.setAutoCommit(true);
		}

		return con;
	}

	public int getActiveConnections() {
		return connectionPool != null ? connectionPool.getHikariPoolMXBean().getActiveConnections() : 0;
	}

	public int getIdleConnections() {
		return connectionPool != null ? connectionPool.getHikariPoolMXBean().getIdleConnections() : 0;
	}

	public static synchronized void shutdown() {
		try {
			if (connectionPool != null) {
				connectionPool.close();
			}
		}
		catch (Exception e) {
			log.warn("Failed to shutdown DatabaseFactory", e);
		}

		connectionPool = null;
	}

	public static void close(PreparedStatement st, Connection con){
		close(st);
		close(con);
	}

	public static void close(PreparedStatement st){
		if(st == null){
			return;
		}
		try{
			if(!st.isClosed()){
				st.close();
			}
		} catch (SQLException e) {
			log.error("Can't close Prepared Statement", e);
		}
	}

	public static void close(Connection con) {
		if (con == null)
			return;

		try{
			if(!con.getAutoCommit()){
				con.setAutoCommit(true);
			}
		} catch (SQLException e) {
			log.error("Failed to set autocommit to true while closing connection: ", e);
		}

		try {
			con.close();
		}
		catch (SQLException e) {
			log.error("DatabaseFactory: Failed to close database connection!", e);
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

	private DatabaseFactory() {
		//
	}
}