/*
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */
package com.aionemu.gameserver.network.sequrity;

import com.aionemu.gameserver.utils.ThreadPoolManager;

/**
 * @author NB4L1
 */
public final class NetFlusher {

	private NetFlusher() {
	}

	public static void add(final Runnable runnable, long interval) {
		ThreadPoolManager.getInstance().scheduleAtFixedRate(() -> {
			try {
				runnable.run();
			} catch (RuntimeException e) {
				e.printStackTrace();
			}
		}, interval, interval);
	}
}
