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
package com.aionemu.commons.configs;

import java.io.File;

import com.aionemu.commons.configuration.Property;

/**
 * Database configuration shared by all server modules.
 *
 * <p>The old BoneCP property names are intentionally kept for compatibility
 * with existing Aion 2.7 configuration files. Runtime pooling is handled by
 * HikariCP in {@code DatabaseFactory}.</p>
 */
public class DatabaseConfig {

	@Property(key = "database.url", defaultValue = "jdbc:mysql://localhost:3306/aion_uni?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC")
	public static String DATABASE_URL;

	@Property(key = "database.driver", defaultValue = "com.mysql.cj.jdbc.Driver")
	public static Class<?> DATABASE_DRIVER;

	@Property(key = "database.user", defaultValue = "root")
	public static String DATABASE_USER;

	@Property(key = "database.password", defaultValue = "root")
	public static String DATABASE_PASSWORD;

	/**
	 * Legacy compatibility key: database.bonecp.partition.count
	 */
	@Property(key = "database.bonecp.partition.count", defaultValue = "2")
	public static int DATABASE_POOL_PARTITION_COUNT;

	/**
	 * Legacy compatibility key: database.bonecp.partition.connections.min
	 */
	@Property(key = "database.bonecp.partition.connections.min", defaultValue = "2")
	public static int DATABASE_POOL_CONNECTIONS_MIN;

	/**
	 * Legacy compatibility key: database.bonecp.partition.connections.max
	 */
	@Property(key = "database.bonecp.partition.connections.max", defaultValue = "5")
	public static int DATABASE_POOL_CONNECTIONS_MAX;

	@Property(key = "database.hikari.connection.timeout", defaultValue = "30000")
	public static long DATABASE_HIKARI_CONNECTION_TIMEOUT;

	@Property(key = "database.hikari.validation.timeout", defaultValue = "5000")
	public static long DATABASE_HIKARI_VALIDATION_TIMEOUT;

	@Property(key = "database.hikari.idle.timeout", defaultValue = "600000")
	public static long DATABASE_HIKARI_IDLE_TIMEOUT;

	@Property(key = "database.hikari.max.lifetime", defaultValue = "1800000")
	public static long DATABASE_HIKARI_MAX_LIFETIME;

	@Property(key = "database.hikari.leak.detection.threshold", defaultValue = "0")
	public static long DATABASE_HIKARI_LEAK_DETECTION_THRESHOLD;

	@Property(key = "database.scriptcontext.descriptor", defaultValue = "./data/scripts/system/database/database.xml")
	public static File DATABASE_SCRIPTCONTEXT_DESCRIPTOR;
}
