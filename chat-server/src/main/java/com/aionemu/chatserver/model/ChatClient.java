package com.aionemu.chatserver.model;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.aionemu.chatserver.model.channel.Channel;
import com.aionemu.chatserver.network.netty.handler.ClientChannelHandler;

public class ChatClient {

	private final int clientId;
	private final byte[] token;
	private final Map<ChannelType, Channel> channels = new ConcurrentHashMap<>();
	private final AtomicInteger channelIndex = new AtomicInteger(1);

	private byte[] identifier;
	private ClientChannelHandler channelHandler;

	public ChatClient(int clientId, byte[] token) {
		this.clientId = clientId;
		this.token = token;
	}

	public void addChannel(Channel channel) {
		channels.put(channel.getChannelType(), channel);
	}

	public boolean isInChannel(Channel channel) {
		return channels.containsKey(channel.getChannelType());
	}

	public int getClientId() {
		return clientId;
	}

	public byte[] getToken() {
		return token;
	}

	public byte[] getIdentifier() {
		return identifier;
	}

	public void setIdentifier(byte[] identifier) {
		this.identifier = identifier;
	}

	public ClientChannelHandler getChannelHandler() {
		return channelHandler;
	}

	public void setChannelHandler(ClientChannelHandler channelHandler) {
		this.channelHandler = channelHandler;
	}

	public int nextIndex() {
		return channelIndex.incrementAndGet();
	}

	public Map<ChannelType, Channel> getChannels() {
		return channels;
	}
}
