/*
 * This file is part of aion-lightning <aion-lightning.org>.
 *
 * aion-lightning is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.aionemu.loginserver.utils;

import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Small named {@link ThreadFactory} for Login Server platform-thread pools.
 *
 * <p>Network/packet/scheduler pools intentionally stay on platform threads.
 * Virtual threads are used only for explicit long-running/blocking tasks in
 * {@link ThreadPoolManager}.</p>
 */
public final class NamedThreadFactory implements ThreadFactory {

	private final String namePrefix;
	private final AtomicInteger sequence = new AtomicInteger(1);
	private final boolean daemon;

	public NamedThreadFactory(String namePrefix) {
		this(namePrefix, false);
	}

	public NamedThreadFactory(String namePrefix, boolean daemon) {
		this.namePrefix = Objects.requireNonNull(namePrefix, "namePrefix");
		this.daemon = daemon;
	}

	@Override
	public Thread newThread(Runnable runnable) {
		Thread thread = new Thread(runnable, namePrefix + sequence.getAndIncrement());
		thread.setDaemon(daemon);
		return thread;
	}
}
