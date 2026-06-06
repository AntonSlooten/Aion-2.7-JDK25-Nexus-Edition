package com.aionemu.chatserver.model;

public enum ChannelType {

	PUBLIC, TRADE, GROUP, JOB, USER;

	public boolean isPublicChannel() {
		return this != USER;
	}

	public boolean isPrivateChannel() {
		return this == USER;
	}
}
