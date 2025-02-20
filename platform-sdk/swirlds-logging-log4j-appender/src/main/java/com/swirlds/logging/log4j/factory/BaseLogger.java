// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.log4j.factory;

import com.swirlds.logging.api.extensions.event.LogEvent;
import com.swirlds.logging.api.extensions.event.LogEventConsumer;
import com.swirlds.logging.api.extensions.event.LogEventFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.Serial;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogBuilder;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.spi.AbstractLogger;

/**
 * Implementation of {@link org.apache.logging.log4j.Logger} that's backed by a {@link LogEventConsumer}.
 * <p>
 * This implementations translates Level and Marker to the base logging.
 * <p>
 * This implementation is inspired by {@code org.apache.logging.log4j.tojul.JULLogger}.
 */
public class BaseLogger extends AbstractLogger {

    /**
     * Serial version UID.
     */
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * The log event consumer to consume log events by the base logging.
     */
    private final LogEventConsumer logEventConsumer;

    /**
     * The log event factory to create log events for the base logging.
     */
    private final LogEventFactory logEventFactory;

    /**
     * Creates a new base logger.
     *
     * @param name the name of the logger
     * @param logEventConsumer the log event consumer
     * @param logEventFactory the log event factory
     */
    public BaseLogger(
            @Nullable final String name,
            @NonNull final LogEventConsumer logEventConsumer,
            @NonNull final LogEventFactory logEventFactory) {
        super(name);
        this.logEventConsumer = Objects.requireNonNull(logEventConsumer, "logEventConsumer is required");
        this.logEventFactory = Objects.requireNonNull(logEventFactory, "logEventFactory is required");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Level getLevel() {
        if (logEventConsumer.isEnabled(getName(), com.swirlds.logging.api.Level.TRACE, null)) {
            return Level.TRACE;
        }
        if (logEventConsumer.isEnabled(getName(), com.swirlds.logging.api.Level.DEBUG, null)) {
            return Level.DEBUG;
        }
        if (logEventConsumer.isEnabled(getName(), com.swirlds.logging.api.Level.INFO, null)) {
            return Level.INFO;
        }
        if (logEventConsumer.isEnabled(getName(), com.swirlds.logging.api.Level.WARN, null)) {
            return Level.WARN;
        }
        if (logEventConsumer.isEnabled(getName(), com.swirlds.logging.api.Level.ERROR, null)) {
            return Level.ERROR;
        }

        return Level.OFF;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEnabled(
            @NonNull final Level level,
            @Nullable final Marker marker,
            @Nullable final Message data,
            @Nullable final Throwable t) {
        return isEnabledFor(level, marker);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEnabled(
            @NonNull final Level level,
            @Nullable final Marker marker,
            @Nullable final CharSequence data,
            @Nullable final Throwable t) {
        return isEnabledFor(level, marker);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEnabled(
            @NonNull final Level level,
            @Nullable final Marker marker,
            @Nullable final Object data,
            @Nullable final Throwable t) {
        return isEnabledFor(level, marker);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEnabled(@NonNull final Level level, @Nullable final Marker marker, @Nullable final String data) {
        return isEnabledFor(level, marker);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEnabled(
            @NonNull final Level level,
            @Nullable final Marker marker,
            @Nullable final String data,
            @Nullable final Object... p1) {
        return isEnabledFor(level, marker);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEnabled(
            @NonNull final Level level,
            @Nullable final Marker marker,
            @Nullable final String message,
            @Nullable final Object p0) {
        return isEnabledFor(level, marker);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEnabled(
            final Level level,
            @Nullable final Marker marker,
            @Nullable final String message,
            @Nullable final Object p0,
            @Nullable final Object p1) {
        return isEnabledFor(level, marker);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEnabled(
            @NonNull final Level level,
            @Nullable final Marker marker,
            @Nullable final String message,
            @Nullable final Object p0,
            @Nullable final Object p1,
            @Nullable final Object p2) {
        return isEnabledFor(level, marker);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEnabled(
            @NonNull final Level level,
            @Nullable final Marker marker,
            @Nullable final String message,
            @Nullable final Object p0,
            @Nullable final Object p1,
            @Nullable final Object p2,
            @Nullable final Object p3) {
        return isEnabledFor(level, marker);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEnabled(
            @NonNull final Level level,
            @Nullable final Marker marker,
            @Nullable final String message,
            @Nullable final Object p0,
            @Nullable final Object p1,
            @Nullable final Object p2,
            @Nullable final Object p3,
            @Nullable final Object p4) {
        return isEnabledFor(level, marker);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEnabled(
            @NonNull final Level level,
            @Nullable final Marker marker,
            @Nullable final String message,
            @Nullable final Object p0,
            @Nullable final Object p1,
            @Nullable final Object p2,
            @Nullable final Object p3,
            @Nullable final Object p4,
            @Nullable final Object p5) {
        return isEnabledFor(level, marker);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEnabled(
            @NonNull final Level level,
            @Nullable final Marker marker,
            @Nullable final String message,
            @Nullable final Object p0,
            @Nullable final Object p1,
            @Nullable final Object p2,
            @Nullable final Object p3,
            @Nullable final Object p4,
            @Nullable final Object p5,
            @Nullable final Object p6) {
        return isEnabledFor(level, marker);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEnabled(
            @NonNull final Level level,
            @Nullable final Marker marker,
            @Nullable final String message,
            @Nullable final Object p0,
            @Nullable final Object p1,
            @Nullable final Object p2,
            @Nullable final Object p3,
            @Nullable final Object p4,
            @Nullable final Object p5,
            @Nullable final Object p6,
            @Nullable final Object p7) {
        return isEnabledFor(level, marker);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEnabled(
            @NonNull final Level level,
            @Nullable final Marker marker,
            @Nullable final String message,
            @Nullable final Object p0,
            @Nullable final Object p1,
            @Nullable final Object p2,
            @Nullable final Object p3,
            @Nullable final Object p4,
            @Nullable final Object p5,
            @Nullable final Object p6,
            @Nullable final Object p7,
            @Nullable final Object p8) {
        return isEnabledFor(level, marker);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEnabled(
            @NonNull final Level level,
            @Nullable final Marker marker,
            @Nullable final String message,
            @Nullable final Object p0,
            @Nullable final Object p1,
            @Nullable final Object p2,
            @Nullable final Object p3,
            @Nullable final Object p4,
            @Nullable final Object p5,
            @Nullable final Object p6,
            @Nullable final Object p7,
            @Nullable final Object p8,
            @Nullable final Object p9) {
        return isEnabledFor(level, marker);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEnabled(
            @NonNull final Level level, @Nullable final Marker marker, final String data, final Throwable t) {
        return isEnabledFor(level, marker);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void logMessage(
            final String fqcn,
            final Level level,
            @Nullable final Marker marker,
            final Message message,
            final Throwable t) {
        final com.swirlds.logging.api.Marker baseMarker = getMarker(marker);
        final Log4JMessage log4JMessage = new Log4JMessage(message);

        final LogEvent event = logEventFactory.createLogEvent(
                convertLevel(level), getName(), log4JMessage, t, baseMarker, ThreadContext.getImmutableContext());
        logEventConsumer.accept(event);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public LogBuilder always() {
        return atLevel(Level.OFF);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public LogBuilder atTrace() {
        return atLevel(Level.TRACE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public LogBuilder atDebug() {
        return atLevel(Level.DEBUG);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public LogBuilder atInfo() {
        return atLevel(Level.INFO);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public LogBuilder atWarn() {
        return atLevel(Level.WARN);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public LogBuilder atError() {
        return atLevel(Level.ERROR);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public LogBuilder atFatal() {
        return atLevel(Level.TRACE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public LogBuilder atLevel(final Level level) {
        return super.atLevel(level);
    }

    /**
     * Checks if the logger is enabled for the given level and marker.
     *
     * @param level the level is required
     * @param marker the marker
     *
     * @return if the logger is enabled
     */
    private boolean isEnabledFor(@NonNull final Level level, @Nullable final Marker marker) {
        final com.swirlds.logging.api.Marker baseMarker = getMarker(marker);
        return logEventConsumer.isEnabled(getName(), convertLevel(level), baseMarker);
    }

    /**
     * Converts the log4j marker to the base logging marker.
     * If the marker is {@code null}, then {@code null} is returned. No marker is provided in the common case, small methods are optimized more effectively.
     *
     * @param marker the log4j marker
     *
     * @return the base logging marker
     */
    @Nullable
    private static com.swirlds.logging.api.Marker getMarker(@Nullable final Marker marker) {
        return marker == null ? null : convertMarker(marker);
    }

    /**
     * Converts the log4j marker to the base logging marker.
     *
     * @param marker the log4j marker
     *
     * @return the base logging marker
     */
    @NonNull
    private static com.swirlds.logging.api.Marker convertMarker(@NonNull final Marker marker) {
        if (marker.getParents() != null) {
            final List<Marker> parents = new ArrayList<>(List.of(marker.getParents()));
            final Marker parent = parents.getFirst();
            parents.removeFirst();
            return new com.swirlds.logging.api.Marker(marker.getName(), convertMarker(parent));
        }
        return new com.swirlds.logging.api.Marker(marker.getName(), null);
    }

    /**
     * Converts the log4j level to the base logging level.
     *
     * @param level the log4j level
     *
     * @return the base logging level
     */
    @NonNull
    public static com.swirlds.logging.api.Level convertLevel(@NonNull final Level level) {
        Objects.requireNonNull(level, "level is required");
        return switch (level.getStandardLevel()) {
            case DEBUG -> com.swirlds.logging.api.Level.DEBUG;
            case TRACE -> com.swirlds.logging.api.Level.TRACE;
            case WARN -> com.swirlds.logging.api.Level.WARN;
            case ERROR -> com.swirlds.logging.api.Level.ERROR;
            default -> com.swirlds.logging.api.Level.INFO;
        };
    }
}
