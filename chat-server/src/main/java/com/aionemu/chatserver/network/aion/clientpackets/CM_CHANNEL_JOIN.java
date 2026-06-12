/*
 * This file is part of Aion X EMU <aionxemu.com>.
 * (License info retained)
 */
package com.aionemu.chatserver.network.aion.clientpackets;

import org.jboss.netty.buffer.ChannelBuffer;
import com.aionemu.chatserver.model.ChatClient;
import com.aionemu.chatserver.model.channel.Channel;
import com.aionemu.chatserver.network.aion.AbstractClientPacket;
import com.aionemu.chatserver.network.aion.serverpackets.SM_CHANNEL_RESPONSE;
import com.aionemu.chatserver.network.netty.handler.ClientChannelHandler;
import com.aionemu.chatserver.service.ChatService;

/**
 * @author SuneC
 */
public class CM_CHANNEL_JOIN extends AbstractClientPacket {

	private int channelIndex;
	private byte[] channelIdentifier;
	// Variabel channelPassword dan log sudah dibuang

	public CM_CHANNEL_JOIN(ChannelBuffer channelBuffer, ClientChannelHandler gameChannelHandler) {
		super(channelBuffer, gameChannelHandler, 0x0D);
	}

	@Override
	protected void readImpl() {
		readC(); // 0x40
		readH(); // 0x00
		channelIndex = readH();
		int length = readH() * 2;
		channelIdentifier = readB(length);
		length = readH() * 2;
		
		// PERBAIKAN: Tetap memajukan pembacaan buffer tanpa menyimpannya
		if (length > 0) {
			readB(length); 
		}
	}

	@Override
	protected void runImpl() {
		ChatClient chatClient = clientChannelHandler.getChatClient();
		Channel channel = ChatService.getInstance().registerPlayerWithChannel(chatClient, channelIndex,
				channelIdentifier);
		if (channel != null) {
			clientChannelHandler.sendPacket(new SM_CHANNEL_RESPONSE(channel, channelIndex));
		}
	}
}