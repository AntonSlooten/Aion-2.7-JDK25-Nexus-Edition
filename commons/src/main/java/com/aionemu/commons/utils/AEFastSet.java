package com.aionemu.commons.utils;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Java 25 compatible set used by legacy Aion code.
 *
 * <p>Preserves deterministic iteration order while avoiding Javolution.</p>
 */
public final class AEFastSet<E> extends AEFastCollection<E> implements Set<E> {

    private final Set<E> delegate;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public AEFastSet() {
        this.delegate = new LinkedHashSet<>();
    }

    public AEFastSet(int capacity) {
        this.delegate = new LinkedHashSet<>(capacity);
    }

    public AEFastSet(Set<? extends E> elements) {
        this.delegate = new LinkedHashSet<>(elements);
    }

    /**
     * Compatibility method for older code that used Javolution shared maps.
     */
    public boolean isShared() {
        return true;
    }

    @Override
    public boolean add(E value) {
        lock.writeLock().lock();
        try {
            return delegate.add(value);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean remove(Object value) {
        lock.writeLock().lock();
        try {
            return delegate.remove(value);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void clear() {
        lock.writeLock().lock();
        try {
            delegate.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean contains(Object value) {
        lock.readLock().lock();
        try {
            return delegate.contains(value);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean isEmpty() {
        lock.readLock().lock();
        try {
            return delegate.isEmpty();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Iterator<E> iterator() {
        lock.readLock().lock();
        try {
            return new LinkedHashSet<>(delegate).iterator();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public int size() {
        lock.readLock().lock();
        try {
            return delegate.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public String toString() {
        lock.readLock().lock();
        try {
            return delegate.toString();
        } finally {
            lock.readLock().unlock();
        }
    }
}
