// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.api.extensions.event;

import com.swirlds.logging.api.Level;
import com.swirlds.logging.api.Marker;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Map;

/**
 * A log event that is passed to the {@link LogEventConsumer} for processing. Normally log events are created by a
 * {@link com.swirlds.logging.api.Logger} or a {@link com.swirlds.logging.api.extensions.provider.LogProvider}
 */
public interface LogEvent {

    /**
     * Returns the log level.
     *
     * @return the log level
     */
    @NonNull
    Level level();

    /**
     * Returns the name of the logger.
     *
     * @return the name of the logger
     */
    @NonNull
    String loggerName();

    /**
     * Returns the name of the thread on that the event has been created.
     *
     * @return the name of the thread
     */
    @NonNull
    String threadName();

    /**
     * Returns the timestamp of the creation of the log event (in ms).
     *
     * @return the timestamp
     */
    long timestamp();

    /**
     * Returns the log message.
     *
     * @return the log message
     */
    @NonNull
    LogMessage message();

    /**
     * Returns the throwable.
     *
     * @return the throwable
     */
    @Nullable
    Throwable throwable();

    /**
     * Returns the marker.
     *
     * @return the marker
     */
    @Nullable
    Marker marker();

    /**
     * Returns the context.
     *
     * @return the context
     */
    @NonNull
    Map<String, String> context();
}
