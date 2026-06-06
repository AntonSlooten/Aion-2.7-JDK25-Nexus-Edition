package com.aionemu.chatserver.common.netty;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractPacketHandler {

	private static final Logger log = LoggerFactory.getLogger(AbstractPacketHandler.class);

	protected static void unknownPacket(int packetId, String state) {
		log.warn("Unknown packet received from Game Server: 0x{} state={}", String.format("%02X", packetId), state);
	}
}
