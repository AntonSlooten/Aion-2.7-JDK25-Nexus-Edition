package com.aionemu.commons.utils.concurrent;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.aionemu.commons.network.util.ThreadUncaughtExceptionHandler;

/**
 * Shared executor factory for Java 25.
 *
 * <p>Use platform-thread executors for long-running game loops and scheduled
 * server work. Use virtual-thread executors only for short blocking tasks such
 * as database, file or network IO helpers.</p>
 */
public final class ModernExecutors {

	private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(2);

	private ModernExecutors() {
	}

	public static ExecutorService newFixedThreadPool(String name, int threads) {
		return Executors.newFixedThreadPool(Math.max(1, threads), new PriorityThreadFactory(name, Thread.NORM_PRIORITY));
	}

	public static ScheduledExecutorService newScheduledThreadPool(String name, int threads) {
		ScheduledExecutorService executor = Executors.newScheduledThreadPool(Math.max(1, threads),
			new PriorityThreadFactory(name, Thread.NORM_PRIORITY));
		if (executor instanceof ThreadPoolExecutor threadPoolExecutor) {
			threadPoolExecutor.setRejectedExecutionHandler(new AionRejectedExecutionHandler());
		}
		return executor;
	}

	public static ExecutorService newCachedThreadPool(String name) {
		return Executors.newCachedThreadPool(new PriorityThreadFactory(name, Thread.NORM_PRIORITY));
	}

	public static ExecutorService newVirtualThreadPerTaskExecutor(String name) {
		Objects.requireNonNull(name, "name");
		return Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
			.name(name + '-', 1)
			.uncaughtExceptionHandler(new ThreadUncaughtExceptionHandler())
			.factory());
	}

	public static void shutdownGracefully(ExecutorService executor) {
		shutdownGracefully(executor, DEFAULT_SHUTDOWN_TIMEOUT);
	}

	public static void shutdownGracefully(ExecutorService executor, Duration timeout) {
		if (executor == null) {
			return;
		}

		executor.shutdown();
		try {
			if (!executor.awaitTermination(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS)) {
				executor.shutdownNow();
			}
		} catch (InterruptedException e) {
			executor.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}
}
