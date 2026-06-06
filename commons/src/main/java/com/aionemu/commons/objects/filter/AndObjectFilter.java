package com.aionemu.commons.objects.filter;

import java.util.List;

/**
 * Combines multiple filters. An object is accepted only when all filters accept it.
 */
public final class AndObjectFilter<T> implements ObjectFilter<T> {

    private final List<ObjectFilter<? super T>> filters;

    @SafeVarargs
    @SuppressWarnings("varargs")
    public AndObjectFilter(ObjectFilter<? super T>... filters) {
        this.filters = List.of(filters);
    }

    @Override
    public boolean acceptObject(T object) {
        for (ObjectFilter<? super T> filter : filters) {
            if (filter != null && !filter.acceptObject(object)) {
                return false;
            }
        }
        return true;
    }
}
