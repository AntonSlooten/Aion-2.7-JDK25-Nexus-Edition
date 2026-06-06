package com.aionemu.commons.callbacks.util;

import java.util.Comparator;

import com.aionemu.commons.callbacks.Callback;

/**
 * Java Collections comparator replacement for the old Javolution FastComparator.
 */
public final class CallbackPriorityFastComparator implements Comparator<Callback<?>> {

    private static final CallbackPriorityComparator DELEGATE = new CallbackPriorityComparator();

    @Override
    public int compare(Callback<?> first, Callback<?> second) {
        return DELEGATE.compare(first, second);
    }
}
