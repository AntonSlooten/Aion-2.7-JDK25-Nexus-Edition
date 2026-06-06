package com.aionemu.chatserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.chatserver.configs.Config;
import com.aionemu.chatserver.network.netty.NettyServer;

public final class ChatServer {

	private static final Logger log = LoggerFactory.getLogger(ChatServer.class);

	private ChatServer() {
	}

	public static void main(String[] args) {
		long startTime = System.currentTimeMillis();

		try {
			printHeader();

			log.info("Loading configuration...");
			Config.load();
			log.info("Configuration loaded successfully.");

			log.info("Starting Netty Server...");
			new NettyServer();
			log.info("Netty Server started successfully.");

			printSystemInfo();

			long bootTime = System.currentTimeMillis() - startTime;
			log.info("============================================================");
			log.info("NEXUS CONNECT CHAT SERVER STARTED SUCCESSFULLY");
			log.info("Boot Time: {} ms", bootTime);
			log.info("============================================================");
		} catch (Throwable t) {
			log.error("Failed to start Chat Server.", t);
			System.exit(1);
		}
	}

	private static void printHeader() {
		log.info("============================================================");
		log.info("                 NEXUS CONNECT CHAT SERVER");
		log.info("============================================================");
	}

	private static void printSystemInfo() {
		Runtime runtime = Runtime.getRuntime();

		log.info("System Information");
		log.info("------------------------------------------------------------");
		log.info("Java Version : {}", System.getProperty("java.version"));
		log.info("Java Vendor  : {}", System.getProperty("java.vendor"));
		log.info("OS Name      : {}", System.getProperty("os.name"));
		log.info("OS Version   : {}", System.getProperty("os.version"));
		log.info("OS Arch      : {}", System.getProperty("os.arch"));
		log.info("Processors   : {}", runtime.availableProcessors());
		log.info("Max Memory   : {} MB", runtime.maxMemory() / 1024 / 1024);
		log.info("Total Memory : {} MB", runtime.totalMemory() / 1024 / 1024);
		log.info("Free Memory  : {} MB", runtime.freeMemory() / 1024 / 1024);
		log.info("------------------------------------------------------------");
	}
}
