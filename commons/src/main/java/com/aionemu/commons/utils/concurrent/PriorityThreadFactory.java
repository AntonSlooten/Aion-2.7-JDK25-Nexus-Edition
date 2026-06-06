package com.aionemu.commons.utils.concurrent;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import com.aionemu.commons.network.util.ThreadUncaughtExceptionHandler;

/**
 * Thread factory that assigns a stable name, priority and uncaught exception handler.
 */
public final class PriorityThreadFactory implements ThreadFactory {

    private final int priority;
    private final String name;
    private final ExecutorService defaultPool;
    private final AtomicInteger threadNumber = new AtomicInteger(1);
    private final ThreadGroup group;

    public PriorityThreadFactory(String name, int priority) {
        this(name, priority, null);
    }

    public PriorityThreadFactory(String name, ExecutorService defaultPool) {
        this(name, Thread.NORM_PRIORITY, defaultPool);
    }

    private PriorityThreadFactory(String name, int priority, ExecutorService defaultPool) {
        this.name = Objects.requireNonNull(name, "name");
        this.priority = priority;
        this.defaultPool = defaultPool;
        this.group = new ThreadGroup(this.name);
    }

    protected ExecutorService getDefaultPool() {
        return defaultPool;
    }

    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(group, runnable);
        thread.setName(name + '-' + threadNumber.getAndIncrement());
        thread.setPriority(priority);
        thread.setUncaughtExceptionHandler(new ThreadUncaughtExceptionHandler());
        return thread;
    }
}
