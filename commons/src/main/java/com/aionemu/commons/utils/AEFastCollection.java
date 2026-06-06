package com.aionemu.commons.utils;

import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Iterator;

/**
 * Compatibility collection used by legacy Aion code.
 *
 * <p>Older versions were backed by Javolution. This implementation uses only
 * the Java Collections API so it is safe on Java 25.</p>
 */
public abstract class AEFastCollection<E> extends AbstractCollection<E> {

    public E getFirst() {
        Iterator<E> iterator = iterator();
        return iterator.hasNext() ? iterator.next() : null;
    }

    public E getLast() {
        E last = null;
        for (E value : this) {
            last = value;
        }
        return last;
    }

    public E removeFirst() {
        Iterator<E> iterator = iterator();
        if (!iterator.hasNext()) {
            return null;
        }

        E value = iterator.next();
        iterator.remove();
        return value;
    }

    public E removeLast() {
        E last = null;
        for (E value : this) {
            last = value;
        }

        if (last != null) {
            remove(last);
        }

        return last;
    }

    public boolean addAll(E[] values) {
        boolean modified = false;
        for (E value : values) {
            modified |= add(value);
        }
        return modified;
    }

    @Override
    public boolean addAll(Collection<? extends E> values) {
        boolean modified = false;
        for (E value : values) {
            modified |= add(value);
        }
        return modified;
    }

    public boolean addAll(Iterable<? extends E> values) {
        boolean modified = false;
        for (E value : values) {
            modified |= add(value);
        }
        return modified;
    }

    public boolean containsAll(Object[] values) {
        for (Object value : values) {
            if (!contains(value)) {
                return false;
            }
        }
        return true;
    }

    public boolean containsAll(Iterable<?> values) {
        for (Object value : values) {
            if (!contains(value)) {
                return false;
            }
        }
        return true;
    }
}
