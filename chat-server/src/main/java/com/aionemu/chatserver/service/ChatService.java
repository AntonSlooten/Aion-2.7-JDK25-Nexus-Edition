package com.aionemu.chatserver.service;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.chatserver.model.ChatClient;
import com.aionemu.chatserver.model.channel.Channel;
import com.aionemu.chatserver.model.channel.Channels;
import com.aionemu.chatserver.network.aion.serverpackets.SM_PLAYER_AUTH_RESPONSE;
import com.aionemu.chatserver.network.netty.handler.ClientChannelHandler;
import com.aionemu.chatserver.network.netty.handler.ClientChannelHandler.State;

public final class ChatService {

	private static final Logger log = LoggerFactory.getLogger(ChatService.class);
	private static final SecureRandom RANDOM = new SecureRandom();

	private final Map<Integer, ChatClient> players = new ConcurrentHashMap<>();

	public static ChatService getInstance() {
		return SingletonHolder.INSTANCE;
	}

	private ChatService() {
	}

	public ChatClient registerPlayer(int playerId, String playerLogin)
			throws NoSuchAlgorithmException, UnsupportedEncodingException {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		byte[] accountToken = digest.digest(playerLogin.getBytes(StandardCharsets.UTF_8));
		byte[] token = generateToken(accountToken);

		ChatClient chatClient = new ChatClient(playerId, token);
		players.put(playerId, chatClient);
		return chatClient;
	}

	private byte[] generateToken(byte[] accountToken) {
		byte[] dynamicToken = new byte[16];
		RANDOM.nextBytes(dynamicToken);

		byte[] token = new byte[48];
		System.arraycopy(dynamicToken, 0, token, 0, dynamicToken.length);
		System.arraycopy(accountToken, 0, token, dynamicToken.length,
				Math.min(accountToken.length, token.length - dynamicToken.length));
		return token;
	}

	public void registerPlayerConnection(int playerId, byte[] token, byte[] identifier,
			ClientChannelHandler channelHandler) {
		ChatClient chatClient = players.get(playerId);
		if (chatClient == null) {
			log.warn("Received chat auth for unknown player id {}", playerId);
			return;
		}

		if (!Arrays.equals(chatClient.getToken(), token)) {
			log.warn("Invalid chat token for player id {}", playerId);
			return;
		}

		chatClient.setIdentifier(identifier);
		chatClient.setChannelHandler(channelHandler);
		channelHandler.sendPacket(new SM_PLAYER_AUTH_RESPONSE());
		channelHandler.setState(State.AUTHED);
		channelHandler.setChatClient(chatClient);
		BroadcastService.getInstance().addClient(chatClient);
	}

	public Channel registerPlayerWithChannel(ChatClient chatClient, int channelIndex, byte[] channelIdentifier) {
		Channel channel = Channels.getChannelByIdentifier(channelIdentifier);
		if (channel != null) {
			chatClient.addChannel(channel);
		}
		return channel;
	}

	public void playerLogout(int playerId) {
		ChatClient chatClient = players.remove(playerId);
		if (chatClient == null) {
			return;
		}

		BroadcastService.getInstance().removeClient(chatClient);
		if (chatClient.getChannelHandler() != null) {
			chatClient.getChannelHandler().close();
		} else {
			log.warn("Received logout event without client authentication for player {}", playerId);
		}
	}

	private static final class SingletonHolder {
		private static final ChatService INSTANCE = new ChatService();
	}
}
