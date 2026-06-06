/*
 * This file is part of aion-unique <aion-unique.org>.
 *
 *  aion-unique is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  aion-unique is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with aion-unique.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aionemu.gameserver.utils;

import com.aionemu.commons.utils.concurrent.AionRejectedExecutionHandler;
import com.aionemu.commons.utils.concurrent.RunnableWrapper;
import com.aionemu.gameserver.configs.main.ThreadConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author -Nemesiss-, NB4L1, MrPoke, lord_rex
 */
public final class ThreadPoolManager {

	private static final Logger log = LoggerFactory.getLogger(ThreadPoolManager.class);

	public static final long MAXIMUM_RUNTIME_IN_MILLISEC_WITHOUT_WARNING = 5000;
	private static final long MAX_DELAY = TimeUnit.NANOSECONDS.toMillis(Long.MAX_VALUE - System.nanoTime()) / 2;

	private final ScheduledThreadPoolExecutor scheduledPool;
	private final ThreadPoolExecutor instantPool;
	private final ExecutorService longRunningPool;
	private final AtomicInteger longRunningActiveTasks = new AtomicInteger();
	private final AtomicInteger longRunningQueuedTasks = new AtomicInteger();

	private ThreadPoolManager() {
		final int instantPoolSize = Math.max(1, ThreadConfig.THREAD_POOL_SIZE / 3);

		scheduledPool = new ScheduledThreadPoolExecutor(ThreadConfig.THREAD_POOL_SIZE - instantPoolSize,
				new NamedThreadFactory("GameServerScheduler-"));
		scheduledPool.setRejectedExecutionHandler(new AionRejectedExecutionHandler());
		scheduledPool.setRemoveOnCancelPolicy(true);
		scheduledPool.prestartAllCoreThreads();

		instantPool = new ThreadPoolExecutor(instantPoolSize, instantPoolSize, 0, TimeUnit.SECONDS,
				new ArrayBlockingQueue<>(100000), new NamedThreadFactory("GameServerInstant-"));
		instantPool.setRejectedExecutionHandler(new AionRejectedExecutionHandler());
		instantPool.prestartAllCoreThreads();

		longRunningPool = Executors.newThreadPerTaskExecutor(
				Thread.ofVirtual().name("GameServerLongRunning-", 1).factory());

		scheduleAtFixedRate(new Runnable() {

			@Override
			public void run() {
				purge();
			}
		}, 150000, 150000);

		log.info("ThreadPoolManager: Initialized with " + scheduledPool.getPoolSize() + " scheduler, "
				+ instantPool.getPoolSize() + " instant and virtual-thread long running executor.");
	}

	private long validate(long delay) {
		return Math.max(0, Math.min(MAX_DELAY, delay));
	}

	private static final class ThreadPoolRunnableWrapper extends RunnableWrapper {

		private ThreadPoolRunnableWrapper(Runnable runnable) {
			super(runnable, ThreadConfig.MAXIMUM_RUNTIME_IN_MILLISEC_WITHOUT_WARNING);
		}
	}

	public final ScheduledFuture<?> schedule(Runnable r, long delay) {
		r = new ThreadPoolRunnableWrapper(r);
		delay = validate(delay);
		return scheduledPool.schedule(r, delay, TimeUnit.MILLISECONDS);
	}

	public final ScheduledFuture<?> scheduleAtFixedRate(Runnable r, long delay, long period) {
		r = new ThreadPoolRunnableWrapper(r);
		delay = validate(delay);
		period = validate(period);
		return scheduledPool.scheduleAtFixedRate(r, delay, period, TimeUnit.MILLISECONDS);
	}

	public final void execute(Runnable r) {
		r = new ThreadPoolRunnableWrapper(r);
		instantPool.execute(r);
	}

	public final void executeLongRunning(Runnable r) {
		longRunningPool.execute(wrapLongRunning(r));
	}

	public final Future<?> submit(Runnable r) {
		r = new ThreadPoolRunnableWrapper(r);

		return instantPool.submit(r);
	}

	public final Future<?> submitLongRunning(Runnable r) {
		return longRunningPool.submit(wrapLongRunning(r));
	}

	private Runnable wrapLongRunning(Runnable runnable) {
		Runnable wrapped = new RunnableWrapper(runnable);
		longRunningQueuedTasks.incrementAndGet();
		return () -> {
			longRunningQueuedTasks.decrementAndGet();
			longRunningActiveTasks.incrementAndGet();
			try {
				wrapped.run();
			} finally {
				longRunningActiveTasks.decrementAndGet();
			}
		};
	}

	/**
	 * Executes a loginServer packet task
	 *
	 * @param pkt runnable packet for Login Server
	 */
	public void executeLsPacket(Runnable pkt) {
		execute(pkt);
	}

	public void purge() {
		scheduledPool.purge();
		instantPool.purge();
	}

	/**
	 * Shutdown all thread pools.
	 */
	public void shutdown() {
		final long begin = System.currentTimeMillis();

		log.info("ThreadPoolManager: Shutting down.");
		log.info("\t... executing " + getTaskCount(scheduledPool) + " scheduled tasks.");
		log.info("\t... executing " + getTaskCount(instantPool) + " instant tasks.");
		log.info("\t... executing " + getLongRunningTaskCount() + " long running tasks.");

		scheduledPool.shutdown();
		instantPool.shutdown();
		longRunningPool.shutdown();

		boolean success = false;
		try {
			success |= awaitTermination(5000);

			scheduledPool.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
			scheduledPool.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);

			success |= awaitTermination(10000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		log.info("\t... success: " + success + " in " + (System.currentTimeMillis() - begin) + " msec.");
		log.info("\t... " + getTaskCount(scheduledPool) + " scheduled tasks left.");
		log.info("\t... " + getTaskCount(instantPool) + " instant tasks left.");
		log.info("\t... " + getLongRunningTaskCount() + " long running tasks left.");
	}

	private int getTaskCount(ThreadPoolExecutor tp) {
		return tp.getQueue().size() + tp.getActiveCount();
	}

	private int getLongRunningTaskCount() {
		return longRunningQueuedTasks.get() + longRunningActiveTasks.get();
	}

	public List<String> getStats() {
		List<String> list = new ArrayList<>();

		list.add("");
		list.add("Scheduled pool:");
		list.add("=================================================");
		list.add("\tgetActiveCount: ...... " + scheduledPool.getActiveCount());
		list.add("\tgetCorePoolSize: ..... " + scheduledPool.getCorePoolSize());
		list.add("\tgetPoolSize: ......... " + scheduledPool.getPoolSize());
		list.add("\tgetLargestPoolSize: .. " + scheduledPool.getLargestPoolSize());
		list.add("\tgetMaximumPoolSize: .. " + scheduledPool.getMaximumPoolSize());
		list.add("\tgetCompletedTaskCount: " + scheduledPool.getCompletedTaskCount());
		list.add("\tgetQueuedTaskCount: .. " + scheduledPool.getQueue().size());
		list.add("\tgetTaskCount: ........ " + scheduledPool.getTaskCount());
		list.add("");
		list.add("Instant pool:");
		list.add("=================================================");
		list.add("\tgetActiveCount: ...... " + instantPool.getActiveCount());
		list.add("\tgetCorePoolSize: ..... " + instantPool.getCorePoolSize());
		list.add("\tgetPoolSize: ......... " + instantPool.getPoolSize());
		list.add("\tgetLargestPoolSize: .. " + instantPool.getLargestPoolSize());
		list.add("\tgetMaximumPoolSize: .. " + instantPool.getMaximumPoolSize());
		list.add("\tgetCompletedTaskCount: " + instantPool.getCompletedTaskCount());
		list.add("\tgetQueuedTaskCount: .. " + instantPool.getQueue().size());
		list.add("\tgetTaskCount: ........ " + instantPool.getTaskCount());
		list.add("");
		list.add("Long running pool:");
		list.add("=================================================");
		list.add("\tvirtualThreads: ...... true");
		list.add("\tgetActiveCount: ...... " + longRunningActiveTasks.get());
		list.add("\tgetQueuedTaskCount: .. " + longRunningQueuedTasks.get());
		list.add("\tgetTaskCount: ........ " + getLongRunningTaskCount());
		list.add("");

		return list;
	}

	private boolean awaitTermination(long timeoutInMillisec) throws InterruptedException {
		final long begin = System.currentTimeMillis();

		while (System.currentTimeMillis() - begin < timeoutInMillisec) {
			if ((!scheduledPool.awaitTermination(10, TimeUnit.MILLISECONDS) && scheduledPool.getActiveCount() > 0)
					|| (!instantPool.awaitTermination(10, TimeUnit.MILLISECONDS) && instantPool.getActiveCount() > 0)) {
				continue;
			}

			if (!longRunningPool.awaitTermination(10, TimeUnit.MILLISECONDS) && getLongRunningTaskCount() > 0) {
				continue;
			}

			return true;
		}

		return false;
	}

	private static final class SingletonHolder {
		private static final ThreadPoolManager INSTANCE = new ThreadPoolManager();
	}

	public static ThreadPoolManager getInstance() {
		return SingletonHolder.INSTANCE;
	}
}
