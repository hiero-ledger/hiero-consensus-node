// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.api.internal.event;

import com.swirlds.logging.api.Level;
import com.swirlds.logging.api.Marker;
import com.swirlds.logging.api.extensions.event.LogEvent;
import com.swirlds.logging.api.extensions.event.LogMessage;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Collections;
import java.util.Map;

/**
 * A mutable log event. The event can be used to not create new log event instances for every log statement.
 */
public class MutableLogEvent implements LogEvent {

    /**
     * The initial values of the log event.
     */
    private static final Level INITIAL_LEVEL = Level.TRACE;

    /**
     * The initial logger name.
     */
    private static final String INITIAL_LOGGER_NAME = "";

    /**
     * The initial thread name.
     */
    private static final String INITIAL_THREAD_NAME = "";

    /**
     * The initial timestamp that is used when the timestamp is undefined.
     */
    private static final long INITIAL_INSTANT = System.currentTimeMillis();

    /**
     * The initial message that is used when the message is undefined.
     */
    private static final LogMessage INITIAL_MESSAGE = new SimpleLogMessage("UNDEFINED");

    /**
     * The initial throwable.
     */
    private static final Throwable INITIAL_THROWABLE = null;

    /**
     * The initial marker.
     */
    private static final Marker INITIAL_MARKER = null;

    /**
     * The initial context.
     */
    private static final Map<String, String> INITIAL_CONTEXT = Map.of();

    /**
     * The log level.
     */
    private Level level;

    /**
     * The name of the logger.
     */
    private String loggerName;

    /**
     * The name of the thread.
     */
    private String threadName;

    /**
     * The timestamp of the log event.
     */
    private long timestamp;

    /**
     * The log message.
     */
    private LogMessage message;

    /**
     * The throwable.
     */
    private Throwable throwable;

    /**
     * The marker.
     */
    private Marker marker;

    /**
     * The context.
     */
    private Map<String, String> context;

    /**
     * Creates a new mutable log event that is filled with dummy values.
     */
    public MutableLogEvent() {
        update(
                INITIAL_LEVEL,
                INITIAL_LOGGER_NAME,
                INITIAL_THREAD_NAME,
                INITIAL_INSTANT,
                INITIAL_MESSAGE,
                INITIAL_THROWABLE,
                INITIAL_MARKER,
                INITIAL_CONTEXT);
    }

    /**
     * Updates the log event with the given values.
     *
     * @param level      The log level
     * @param loggerName The name of the logger
     * @param threadName The name of the thread
     * @param timestamp  The timestamp of the log event
     * @param message    The log message (this is not a String since the message can be parameterized)
     * @param throwable  The throwable
     * @param marker     The marker
     * @param context    The context
     */
    public void update(
            @NonNull final Level level,
            @NonNull final String loggerName,
            @NonNull final String threadName,
            final long timestamp,
            @NonNull final LogMessage message,
            @Nullable final Throwable throwable,
            @Nullable final Marker marker,
            @NonNull final Map<String, String> context) {
        this.level = level;
        this.loggerName = loggerName;
        this.threadName = threadName;
        this.timestamp = timestamp;
        this.message = message;
        this.throwable = throwable;
        this.marker = marker;
        this.context = Collections.unmodifiableMap(context);
    }

    @Override
    @NonNull
    public Level level() {
        return level;
    }

    @Override
    @NonNull
    public String loggerName() {
        return loggerName;
    }

    @Override
    @NonNull
    public String threadName() {
        return threadName;
    }

    @Override
    public long timestamp() {
        return timestamp;
    }

    @Override
    @NonNull
    public LogMessage message() {
        return message;
    }

    @Override
    @Nullable
    public Throwable throwable() {
        return throwable;
    }

    @Override
    @Nullable
    public Marker marker() {
        return marker;
    }

    @Override
    @NonNull
    public Map<String, String> context() {
        return context;
    }
}
