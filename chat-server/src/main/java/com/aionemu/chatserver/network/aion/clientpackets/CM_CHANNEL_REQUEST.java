package com.aionemu.chatserver.network.aion.clientpackets;

import java.nio.charset.StandardCharsets;

import org.jboss.netty.buffer.ChannelBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.chatserver.model.ChatClient;
import com.aionemu.chatserver.model.channel.Channel;
import com.aionemu.chatserver.network.aion.AbstractClientPacket;
import com.aionemu.chatserver.network.aion.serverpackets.SM_CHANNEL_RESPONSE;
import com.aionemu.chatserver.network.netty.handler.ClientChannelHandler;
import com.aionemu.chatserver.service.ChatService;

public final class CM_CHANNEL_REQUEST extends AbstractClientPacket {

	private static final Logger log = LoggerFactory.getLogger(CM_CHANNEL_REQUEST.class);

	private int channelIndex;
	private byte[] channelIdentifier;

	public CM_CHANNEL_REQUEST(ChannelBuffer channelBuffer, ClientChannelHandler gameChannelHandler) {
		super(channelBuffer, gameChannelHandler, 0x10);
	}

	@Override
	protected void readImpl() {
		readC(); // 0x40
		readH(); // 0x00

		channelIndex = readH();

		int length = readH() * 2;
		channelIdentifier = readB(length);
	}

	@Override
	protected void runImpl() {
		if (channelIdentifier == null || channelIdentifier.length == 0) {
			return;
		}

		ChatClient chatClient = clientChannelHandler.getChatClient();

		if (chatClient == null) {
			log.warn("Channel request ignored because chat client is not initialized. index={}", channelIndex);
			return;
		}

		Channel channel = ChatService.getInstance().registerPlayerWithChannel(chatClient, channelIndex,
				channelIdentifier);

		if (channel != null) {
			clientChannelHandler.sendPacket(new SM_CHANNEL_RESPONSE(channel, channelIndex));
			return;
		}

		String channelName = decodeChannelIdentifier(channelIdentifier);

		if (isOptionalClientChannel(channelName)) {
			log.debug("Optional client chat channel ignored. index={}, channel={}", channelIndex, channelName);
			return;
		}

		log.warn("Unknown chat channel request ignored. index={}, channel={}", channelIndex, channelName);
	}

	private static String decodeChannelIdentifier(byte[] identifier) {
		if (identifier == null || identifier.length == 0) {
			return "";
		}

		return new String(identifier, StandardCharsets.UTF_16LE).replace("\u0000", "").trim();
	}

	private static boolean isOptionalClientChannel(String channelName) {
		if (channelName == null || channelName.isBlank()) {
			return true;
		}

		return channelName.startsWith("@job_") || channelName.contains(".AION.KOR") || channelName.contains(".AION.");
	}

	@Override
	public String toString() {
		return "CM_CHANNEL_REQUEST[channelIndex=" + channelIndex + ", channelIdentifier="
				+ decodeChannelIdentifier(channelIdentifier) + "]";
	}
}