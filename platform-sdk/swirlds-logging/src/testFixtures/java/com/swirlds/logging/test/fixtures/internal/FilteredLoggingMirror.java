// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.test.fixtures.internal;

import com.swirlds.logging.api.extensions.event.LogEvent;
import com.swirlds.logging.test.fixtures.LoggingMirror;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.AbstractList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * A concrete implementation of the {@link LoggingMirror} interface that represents a filtered view of log events based
 * on a provided filter function.
 */
public class FilteredLoggingMirror implements LoggingMirror {

    private final Predicate<LogEvent> filter;
    private final List<LogEvent> list;
    private final Runnable closeAction;

    /**
     * Constructs a new {@code FilteredLoggingMirror} instance with the specified parameters.
     *
     * @param list          The list of log events to filter.
     * @param filter        The filter function used to select log events.
     * @param closeAction The action to be executed when this mirror is closed.
     * @throws NullPointerException if one of the arguments is {@code null}
     */
    public FilteredLoggingMirror(
            @NonNull final List<LogEvent> list,
            @NonNull final Predicate<LogEvent> filter,
            @NonNull final Runnable closeAction) {
        this.list = Objects.requireNonNull(list, "list must not be null");
        this.filter = Objects.requireNonNull(filter, "filter must not be null");
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction must not be null");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<LogEvent> getEvents() {
        return list.stream().filter(filter).toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public LoggingMirror filter(@NonNull final Predicate<LogEvent> filter) {
        Objects.requireNonNull(filter, "filter must not be null");
        final List<LogEvent> liveList = new AbstractList<>() {
            @Override
            public int size() {
                return list.size();
            }

            @Override
            public LogEvent get(int index) {
                return list.get(index);
            }
        };
        return new FilteredLoggingMirror(liveList, this.filter.and(filter), closeAction);
    }

    /**
     * Clears the mirror and disposes it. This method is automatically called before and after a test.
     */
    @Override
    public void close() {
        closeAction.run();
    }
}
