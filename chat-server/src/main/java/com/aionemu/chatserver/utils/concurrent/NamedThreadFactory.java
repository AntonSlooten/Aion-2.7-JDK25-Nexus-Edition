/*
 * This file is part of Aion X EMU <aionxemu.com>.
 *
 *  This is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This software is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this software. If not, see <http://www.gnu.org/licenses/>.
 */
package com.aionemu.chatserver.utils.concurrent;

import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Small Java 25 friendly thread factory used by Chat Server infrastructure.
 *
 * <p>
 * Netty 3 is not virtual-thread aware, so all networking threads remain
 * platform threads. The value here is deterministic names, daemon control and a
 * single uncaught-exception handler.
 * </p>
 */
public final class NamedThreadFactory implements ThreadFactory {
	private final String namePrefix;
	private final boolean daemon;
	private final AtomicInteger sequence = new AtomicInteger();

	public NamedThreadFactory(String namePrefix) {
		this(namePrefix, false);
	}

	public NamedThreadFactory(String namePrefix, boolean daemon) {
		this.namePrefix = Objects.requireNonNull(namePrefix, "namePrefix");
		this.daemon = daemon;
	}

	@Override
	public Thread newThread(Runnable task) {
		Thread thread = Thread.ofPlatform().name(namePrefix + '-' + sequence.incrementAndGet()).daemon(daemon)
				.unstarted(task);
		thread.setUncaughtExceptionHandler((failedThread, cause) -> System.err
				.println("Uncaught exception in " + failedThread.getName() + ": " + cause));
		return thread;
	}
}
