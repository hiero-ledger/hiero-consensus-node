// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Represents an action that should be performed when an entry passes a filter.
 */
public class FilterActionPair<T> {

    private Predicate<T> filter;
    private Consumer<T> action;

    /**
     * Create a new pair.
     *
     * @param filter
     * 		the filter to be checked. If the predicate returns true then the entry is allowed
     * 		to pass, otherwise it is rejected. If null then all entries are considered to pass the filter.
     * @param action
     * 		the action to perform if an event passes the filter.
     */
    public FilterActionPair(Predicate<T> filter, Consumer<T> action) {
        this.filter = filter;
        this.action = action;
    }

    /**
     * Check if the entry passes the filter, and if it does then perform the configured action.
     *
     * @param entry
     * 		the entry that is being handled
     */
    public void handle(T entry) {
        if (filter == null || filter.test(entry)) {
            action.accept(entry);
        }
    }
}
