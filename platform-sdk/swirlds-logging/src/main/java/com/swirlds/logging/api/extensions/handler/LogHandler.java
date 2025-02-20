// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.api.extensions.handler;

import com.swirlds.config.api.Configuration;
import com.swirlds.logging.api.Level;
import com.swirlds.logging.api.Marker;
import com.swirlds.logging.api.extensions.event.LogEvent;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * A log handler that handles log events. A log handler can be used to write log events to a file, send them to a remote
 * server, or do any other kind of processing. A log handler is created by a {@link LogHandlerFactory} that use the Java
 * SPI.
 *
 * @see LogHandlerFactory
 */
public interface LogHandler {
    String PROPERTY_HANDLER = "logging.handler.%s";
    String PROPERTY_HANDLER_ENABLED = PROPERTY_HANDLER + ".enabled";

    /**
     * Returns the name of the log handler.
     *
     * @return the name of the log handler
     */
    @NonNull
    default String getName() {
        return getClass().getSimpleName();
    }

    /**
     * Returns true if the log handler is active, false otherwise. If the log handler is not active, it will not be
     * used. This can be used to disable a log handler without removing it from the configuration. The current logging
     * implementation checks that state at startup and not for every log event.
     *
     * @return true if the log handler is active, false otherwise
     */
    default boolean isActive() {
        return true;
    }

    /**
     * Calling that method will stop the log handler and finalize it. This can be used to close files or flush streams.
     */
    default void stopAndFinalize() {}

    /**
     * All content for this handler that has been buffered will be written to destination.
     */
    default void flush() {}

    /**
     * Updates the log handler with the new configuration.
     *
     * @param configuration the new configuration
     */
    default void update(@NonNull final Configuration configuration) {}

    /**
     * Checks if the consumer is enabled for the given name and level.
     *
     * @param name  the name
     * @param level the level
     * @return true if the consumer is enabled, false otherwise
     */
    boolean isEnabled(@NonNull String name, @NonNull Level level, @Nullable Marker marker);

    /**
     * Makes this Handler process the Event.
     * @param event the event to handle
     */
    void handle(@NonNull LogEvent event);
}
