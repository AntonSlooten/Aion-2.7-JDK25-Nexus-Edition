package com.aionemu.chatserver.common.netty;

public abstract class AbstractPacket {

	private final int opCode;

	protected AbstractPacket(int opCode) {
		this.opCode = opCode;
	}

	public final int getOpCode() {
		return opCode;
	}
}
