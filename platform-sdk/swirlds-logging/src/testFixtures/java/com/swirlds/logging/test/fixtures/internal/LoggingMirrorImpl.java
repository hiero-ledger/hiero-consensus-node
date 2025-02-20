// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.test.fixtures.internal;

import com.swirlds.logging.api.Level;
import com.swirlds.logging.api.Marker;
import com.swirlds.logging.api.extensions.event.LogEvent;
import com.swirlds.logging.api.extensions.handler.LogHandler;
import com.swirlds.logging.api.internal.DefaultLoggingSystem;
import com.swirlds.logging.test.fixtures.LoggingMirror;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * A concrete implementation of the {@link LoggingMirror} interface that serves as a logging mirror and also implements
 * the {@link LogHandler} interface to receive and store log events.
 */
public class LoggingMirrorImpl implements LoggingMirror, LogHandler {

    private final List<LogEvent> events = new CopyOnWriteArrayList<>();

    /**
     * Constructs a new {@code LoggingMirrorImpl} instance. It registers itself as a log handler with the default
     * logging system to receive log events.
     */
    public LoggingMirrorImpl() {}

    /**
     * {@inheritDoc}
     */
    @Override
    public void handle(@NonNull final LogEvent event) {
        events.add(event);
    }

    /**
     * Clears the mirror and disposes it. This method is automatically called before and after a test.
     */
    @Override
    public void close() {
        DefaultLoggingSystem.getInstance().removeHandler(this);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public LoggingMirror filter(@NonNull final Predicate<LogEvent> filter) {
        return new FilteredLoggingMirror(events, filter, this::close);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<LogEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public String getName() {
        return "LoggingMirror";
    }

    /**
     * Checks if the consumer is enabled for the given name and level.
     *
     * @param name   the name
     * @param level  the level
     * @param marker
     * @return true if the consumer is enabled, false otherwise
     */
    @Override
    public boolean isEnabled(@NonNull final String name, @NonNull final Level level, @Nullable final Marker marker) {
        return true;
    }
}
