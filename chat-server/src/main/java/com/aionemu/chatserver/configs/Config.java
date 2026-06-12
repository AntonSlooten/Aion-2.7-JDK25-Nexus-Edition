package com.aionemu.chatserver.configs;

import java.net.InetSocketAddress;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.commons.configuration.ConfigurableProcessor;
import com.aionemu.commons.configuration.Property;
import com.aionemu.commons.utils.PropertiesUtils;

public final class Config {

	private static final Logger log = LoggerFactory.getLogger(Config.class);

	@Property(key = "chatserver.network.client.address", defaultValue = "localhost:10241")
	public static InetSocketAddress CHAT_ADDRESS;

	@Property(key = "chatserver.network.gameserver.address", defaultValue = "localhost:9021")
	public static InetSocketAddress GAME_ADDRESS;

	@Property(key = "chatserver.network.gameserver.password", defaultValue = "*")
	public static String GAME_SERVER_PASSWORD;

	private Config() {
	}

	public static void load() {
		try {
			Properties myProps = null;
			try {
				log.info("Loading: mycs.properties");
				myProps = PropertiesUtils.load("./config/mycs.properties");
			} catch (Exception e) {
				log.info("No override properties found");
			}
			
			Properties[] properties = PropertiesUtils.loadAllFromDirectory("./config");
			PropertiesUtils.overrideProperties(properties, myProps);
			ConfigurableProcessor.process(Config.class, properties);
			log.info("Chat Server configuration loaded.");
			log.info("Client Address    : {}", CHAT_ADDRESS);
			log.info("GameServer Address: {}", GAME_ADDRESS);
		} catch (Exception e) {
			log.error("Can't load Chat Server configuration.", e);
			throw new IllegalStateException("Can't load Chat Server configuration.", e);
		}
	}
}
