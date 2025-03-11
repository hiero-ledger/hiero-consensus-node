// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.api.internal;

import com.swirlds.logging.api.Level;
import com.swirlds.logging.api.Logger;
import com.swirlds.logging.api.Marker;
import com.swirlds.logging.api.extensions.emergency.EmergencyLogger;
import com.swirlds.logging.api.extensions.emergency.EmergencyLoggerProvider;
import com.swirlds.logging.api.extensions.event.LogEvent;
import com.swirlds.logging.api.extensions.event.LogEventConsumer;
import com.swirlds.logging.api.extensions.event.LogEventFactory;
import com.swirlds.logging.api.extensions.event.LogMessage;
import com.swirlds.logging.api.internal.event.ParameterizedLogMessage;
import com.swirlds.logging.api.internal.event.SimpleLogMessage;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The implementation of the logger.
 */
public class LoggerImpl implements Logger {

    /**
     * The emergency logger that is used to log errors that occur during the logging process.
     */
    private static final EmergencyLogger EMERGENCY_LOGGER = EmergencyLoggerProvider.getEmergencyLogger();

    /**
     * The name of the logger.
     */
    private final String name;

    /**
     * The marker of the logger.
     */
    private final Marker marker;

    /**
     * The context of the logger.
     */
    private final Map<String, String> context;

    /**
     * The consumer that is used to consume the log events.
     */
    private final LogEventConsumer logEventConsumer;

    /**
     * The factory that is used to create the log events.
     */
    private final LogEventFactory logEventFactory;

    /**
     * Creates a new instance of the logger.
     *
     * @param name             the name of the logger
     * @param marker           the initial marker of the logger (if present)
     * @param context          the initial context of the logger
     * @param logEventConsumer the consumer that is used to consume the log events
     * @throws NullPointerException if the logEventFactory logEventConsumer or name is null.
     */
    protected LoggerImpl(
            @NonNull final String name,
            @Nullable final Marker marker,
            @Nullable final Map<String, String> context,
            @NonNull final LogEventFactory logEventFactory,
            @NonNull final LogEventConsumer logEventConsumer) {
        this.name = Objects.requireNonNull(
                name, "name must not be null"); // Callers of this method need to make sure this is never possible
        this.marker = marker;
        this.context = (context == null) ? Collections.emptyMap() : Collections.unmodifiableMap(context);
        this.logEventFactory = Objects.requireNonNull(logEventFactory, "logEventFactory must not be null");
        this.logEventConsumer = Objects.requireNonNull(logEventConsumer, "logEventConsumer must not be null");
    }

    /**
     * Creates a new instance of the logger.
     *
     * @param name             the name of the logger
     * @param logEventConsumer the consumer that is used to consume the log events
     * @throws NullPointerException if the logEventConsumer is null. For all other use cases fallbacks are implemented
     */
    public LoggerImpl(
            @NonNull final String name,
            @NonNull final LogEventFactory logEventFactory,
            @NonNull final LogEventConsumer logEventConsumer) {
        this(name, null, Map.of(), logEventFactory, logEventConsumer);
    }

    /**
     * Returns the name of the logger.
     *
     * @return the name of the logger
     */
    @NonNull
    public String getName() {
        return name;
    }

    @Override
    public void log(@NonNull final Level level, @NonNull final String message) {
        log(level, message, (Throwable) null);
    }

    @Override
    public void log(@NonNull final Level level, @NonNull final String message, @Nullable final Throwable throwable) {
        logImpl(level, new SimpleLogMessage(message), throwable);
    }

    @Override
    public void log(@NonNull final Level level, @NonNull final String message, @Nullable final Object... args) {
        logImpl(level, new ParameterizedLogMessage(message, args), null);
    }

    @Override
    public void log(@NonNull final Level level, @NonNull final String message, @Nullable final Object arg) {
        logImpl(level, new ParameterizedLogMessage(message, arg), null);
    }

    @Override
    public void log(
            @NonNull final Level level,
            @NonNull final String message,
            @Nullable final Object arg1,
            @Nullable final Object arg2) {
        logImpl(level, new ParameterizedLogMessage(message, arg1, arg2), null);
    }

    @Override
    public void log(
            @NonNull final Level level,
            @NonNull final String message,
            @Nullable final Throwable throwable,
            @Nullable final Object... args) {
        logImpl(level, new ParameterizedLogMessage(message, args), throwable);
    }

    @Override
    public void log(
            @NonNull final Level level,
            @NonNull final String message,
            @Nullable final Throwable throwable,
            @Nullable final Object arg1) {
        logImpl(level, new ParameterizedLogMessage(message, arg1), throwable);
    }

    @Override
    public void log(
            @NonNull final Level level,
            @NonNull final String message,
            @Nullable final Throwable throwable,
            @Nullable final Object arg1,
            @Nullable final Object arg2) {
        logImpl(level, new ParameterizedLogMessage(message, arg1, arg2), throwable);
    }

    @Override
    @NonNull
    public Logger withMarker(@NonNull final String markerName) {
        if (markerName == null) {
            EMERGENCY_LOGGER.logNPE("markerName");
            return this;
        } else {
            return withMarkerAndContext(new Marker(markerName, marker), context);
        }
    }

    @Override
    @NonNull
    public Logger withContext(@NonNull final String key, @Nullable final String value) {
        if (key == null) {
            EMERGENCY_LOGGER.logNPE("key");
            return this;
        }
        final Map<String, String> newContext = new HashMap<>(context);
        newContext.put(key, value);

        return withMarkerAndContext(marker, newContext);
    }

    @Override
    @NonNull
    public Logger withContext(final @NonNull String key, final @Nullable String... values) {
        if (key == null) {
            EMERGENCY_LOGGER.logNPE("key");
            return this;
        }
        final Map<String, String> newContext = new HashMap<>(context);
        if (values != null) {
            newContext.put(key, String.join(",", values));
        } else {
            newContext.put(key, null);
        }

        return withMarkerAndContext(marker, newContext);
    }

    @Override
    public boolean isEnabled(final @NonNull Level level) {
        return logEventConsumer.isEnabled(getName(), level, marker);
    }

    /**
     * Creates a new instance as a copy of this logger with the given marker and context.
     *
     * @param marker  the marker
     * @param context the context
     * @return the new logger
     */
    @NonNull
    protected Logger withMarkerAndContext(@Nullable final Marker marker, @NonNull final Map<String, String> context) {
        return new LoggerImpl(getName(), marker, context, logEventFactory, logEventConsumer);
    }

    /**
     * Logs the given message and throwable.
     *
     * @param level     the level
     * @param message   the message
     * @param throwable the throwable
     */
    public void logImpl(
            @NonNull final Level level, @NonNull final LogMessage message, @Nullable final Throwable throwable) {
        if (isEnabled(level)) {
            Marker marker = this.marker;
            LogEvent logEvent = logEventFactory.createLogEvent(level, getName(), message, throwable, marker, context);
            logEventConsumer.accept(logEvent);
        }
    }
}
