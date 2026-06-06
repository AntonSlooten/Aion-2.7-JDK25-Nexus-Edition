package com.aionemu.chatserver.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.aionemu.chatserver.model.ChatClient;
import com.aionemu.chatserver.model.message.Message;
import com.aionemu.chatserver.network.aion.serverpackets.SM_CHANNEL_MESSAGE;
import com.aionemu.chatserver.network.netty.handler.ClientChannelHandler;

public final class BroadcastService {

	private final Map<Integer, ChatClient> clients = new ConcurrentHashMap<>();

	public static BroadcastService getInstance() {
		return SingletonHolder.INSTANCE;
	}

	private BroadcastService() {
	}

	public void addClient(ChatClient client) {
		clients.put(client.getClientId(), client);
	}

	public void removeClient(ChatClient client) {
		clients.remove(client.getClientId());
	}

	public void broadcastMessage(Message message) {
		for (ChatClient client : clients.values()) {
			if (client.isInChannel(message.getChannel())) {
				sendMessage(client, message);
			}
		}
	}

	public void sendMessage(ChatClient chatClient, Message message) {
		ClientChannelHandler channelHandler = chatClient.getChannelHandler();
		if (channelHandler != null) {
			channelHandler.sendPacket(new SM_CHANNEL_MESSAGE(message));
		}
	}

	private static final class SingletonHolder {
		private static final BroadcastService INSTANCE = new BroadcastService();
	}
}
