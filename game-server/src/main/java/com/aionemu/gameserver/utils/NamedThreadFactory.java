/*
 * This file is part of aion-unique <aion-unique.org>.
 */
package com.aionemu.gameserver.utils;

import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Small named thread factory for server owned platform threads.
 */
public final class NamedThreadFactory implements ThreadFactory {

	private final ThreadGroup group;
	private final AtomicInteger threadNumber = new AtomicInteger(1);
	private final String namePrefix;
	private final boolean daemon;

	public NamedThreadFactory(String namePrefix) {
		this(namePrefix, false);
	}

	public NamedThreadFactory(String namePrefix, boolean daemon) {
		this.namePrefix = Objects.requireNonNull(namePrefix, "namePrefix");
		this.daemon = daemon;
		this.group = Thread.currentThread().getThreadGroup();
	}

	@Override
	public Thread newThread(Runnable runnable) {
		Thread thread = new Thread(group, runnable, namePrefix + threadNumber.getAndIncrement(), 0);
		thread.setDaemon(daemon);
		thread.setPriority(Thread.NORM_PRIORITY);
		return thread;
	}
}
