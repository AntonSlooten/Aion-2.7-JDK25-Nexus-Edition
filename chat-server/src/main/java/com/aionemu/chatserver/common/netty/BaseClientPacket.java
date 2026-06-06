package com.aionemu.chatserver.common.netty;

import org.jboss.netty.buffer.ChannelBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class BaseClientPacket extends AbstractPacket {

	private static final Logger log = LoggerFactory.getLogger(BaseClientPacket.class);

	private final ChannelBuffer buffer;

	protected BaseClientPacket(ChannelBuffer buffer, int opCode) {
		super(opCode);
		this.buffer = buffer;
	}

	public final int getRemainingBytes() {
		return buffer.readableBytes();
	}

	public final boolean read() {
		try {
			readImpl();
			if (getRemainingBytes() > 0) {
				log.debug("Packet {} was not fully read. Remaining bytes: {}", this, getRemainingBytes());
			}
			return true;
		} catch (Exception e) {
			log.error("Reading failed for packet {}", this, e);
			return false;
		}
	}

	public final void run() {
		try {
			runImpl();
		} catch (Exception e) {
			log.error("Running failed for packet {}", this, e);
		}
	}

	protected abstract void readImpl();

	protected abstract void runImpl();

	protected final int readD() {
		try {
			return buffer.readInt();
		} catch (Exception e) {
			log.error("Missing D for packet {}", this, e);
			return 0;
		}
	}

	protected final int readC() {
		try {
			return buffer.readByte() & 0xFF;
		} catch (Exception e) {
			log.error("Missing C for packet {}", this, e);
			return 0;
		}
	}

	protected final int readH() {
		try {
			return buffer.readShort() & 0xFFFF;
		} catch (Exception e) {
			log.error("Missing H for packet {}", this, e);
			return 0;
		}
	}

	protected final double readDF() {
		try {
			return buffer.readDouble();
		} catch (Exception e) {
			log.error("Missing DF for packet {}", this, e);
			return 0D;
		}
	}

	protected final float readF() {
		try {
			return buffer.readFloat();
		} catch (Exception e) {
			log.error("Missing F for packet {}", this, e);
			return 0F;
		}
	}

	protected final long readQ() {
		try {
			return buffer.readLong();
		} catch (Exception e) {
			log.error("Missing Q for packet {}", this, e);
			return 0L;
		}
	}

	protected final String readS() {
		StringBuilder builder = new StringBuilder();
		try {
			char ch;
			while ((ch = buffer.readChar()) != 0) {
				builder.append(ch);
			}
		} catch (Exception e) {
			log.error("Missing S for packet {}", this, e);
		}
		return builder.toString();
	}

	protected final byte[] readB(int length) {
		byte[] result = new byte[length];
		try {
			buffer.readBytes(result);
		} catch (Exception e) {
			log.error("Missing byte[{}] for packet {}", length, this, e);
		}
		return result;
	}
}
