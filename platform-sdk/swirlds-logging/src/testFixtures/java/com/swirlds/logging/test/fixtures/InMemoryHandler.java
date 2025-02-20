// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.test.fixtures;

import com.swirlds.config.api.Configuration;
import com.swirlds.logging.api.extensions.event.LogEvent;
import com.swirlds.logging.api.extensions.handler.AbstractLogHandler;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A simple implementation of the {@code LogHandler} interface that stores log events in memory.
 *
 * <p>
 * The {@code InMemoryHandler} class is a basic log handler that keeps a record of log events
 * in an in-memory list. It implements the {@code LogHandler} interface to provide a simple way
 * to capture and retrieve log events during the application's execution.
 * </p>
 *
 * <p>
 * Each log event accepted by this handler is added to an internal list. You can later retrieve
 * the stored log events using the {@code getEvents} method, which returns an unmodifiable list
 * of log events.
 * </p>
 *
 * <p>
 * Note that this handler does not provide any advanced logging capabilities, such as log rotation,
 * persistence, or remote logging. It is primarily intended for debugging and testing purposes
 * where you need to capture log events in memory for inspection.
 * </p>
 */
public class InMemoryHandler extends AbstractLogHandler {

    private final List<LogEvent> events = new CopyOnWriteArrayList<>();

    /**
     * Creates a new log handler.
     *
     * @param configKey     the configuration key
     * @param configuration the configuration
     */
    public InMemoryHandler(final String configKey, final Configuration configuration) {
        super(configKey, configuration);
    }

    /**
     * Creates a new log handler.
     *
     * @param configuration the configuration
     */
    public InMemoryHandler(final Configuration configuration) {
        this("inMemory", configuration);
    }

    /**
     * InMemoryHandler is always enabled.
     *
     * @return {@code true} to indicate that this log handler is always active
     */
    @Override
    public boolean isActive() {
        return true;
    }

    /**
     * Accepts a log event and stores it in memory.
     *
     * @param event the log event to be accepted and recorded
     */
    @Override
    public void handle(@NonNull LogEvent event) {
        events.add(event);
    }

    /**
     * Retrieves an unmodifiable list of the log events stored in memory.
     *
     * @return an unmodifiable list of log events recorded by this handler
     */
    public List<LogEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }
}
