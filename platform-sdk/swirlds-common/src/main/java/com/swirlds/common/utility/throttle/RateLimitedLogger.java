// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.utility.throttle;

import com.swirlds.base.time.Time;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.Objects;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.hiero.base.concurrent.locks.AutoClosableLock;
import org.hiero.base.concurrent.locks.Locks;
import org.hiero.base.concurrent.locks.locked.Locked;

/**
 * A wrapper around a {@link Logger} that provides rate limiting.
 */
public class RateLimitedLogger {

    private final Logger logger;
    private final RateLimiter rateLimiter;
    private final AutoClosableLock lock;

    /**
     * Create a new {@link RateLimitedLogger} that will log at most {@code maxFrequency} times per second.
     *
     * @param logger       the logger to wrap
     * @param time         provides wall clock time
     * @param maxFrequency the maximum frequency at which to log
     */
    public RateLimitedLogger(@NonNull final Logger logger, @NonNull final Time time, final double maxFrequency) {
        this(logger, new RateLimiter(time, maxFrequency));
    }

    /**
     * Create a new {@link RateLimitedLogger} that will log at most once per {@code minimumPeriod}.
     *
     * @param logger        the logger to wrap
     * @param time          provides wall clock time
     * @param minimumPeriod the minimum amount of time that must pass between log messages
     */
    public RateLimitedLogger(
            @NonNull final Logger logger, @NonNull final Time time, @NonNull final Duration minimumPeriod) {
        this(logger, new RateLimiter(time, minimumPeriod));
    }

    /**
     * Constructor.
     *
     * @param logger      the logger to wrap
     * @param rateLimiter the rate limiter to use
     */
    private RateLimitedLogger(@NonNull final Logger logger, @NonNull final RateLimiter rateLimiter) {
        this.logger = Objects.requireNonNull(logger);
        this.rateLimiter = Objects.requireNonNull(rateLimiter);
        lock = Locks.createAutoLock();
    }

    /**
     * Generate the log message.
     *
     * @param baseMessage    the base log message
     * @param deniedRequests the number of times we have attempted to log this message but have been denied
     * @return the message to forward to the wrapped logger
     */
    @NonNull
    private static String generateMessage(@NonNull final String baseMessage, final long deniedRequests) {
        if (deniedRequests > 0) {
            return baseMessage + "\n(Due to rate limiting, this condition has been triggered "
                    + deniedRequests
                    + " times without being reported.)";
        } else {
            return baseMessage;
        }
    }

    /**
     * Write a message to the log at the given level. Message may not actually be written if this logger has been used
     * too recently.
     *
     * @param level     the log level
     * @param logMarker the marker to use
     * @param message   the message to write
     * @param varargs   optional arguments to pass to the log4j logger
     */
    public void log(
            @NonNull final Level level,
            @NonNull final Marker logMarker,
            @NonNull final String message,
            @Nullable final Object... varargs) {

        Objects.requireNonNull(level);
        Objects.requireNonNull(logMarker);
        Objects.requireNonNull(message);

        try (final Locked l = lock.lock()) {
            final long deniedRequests = rateLimiter.getDeniedRequests();
            if (rateLimiter.requestAndTrigger()) {
                logger.log(level, logMarker, generateMessage(message, deniedRequests), varargs);
            }
        }
    }

    /**
     * Write a message to the log at debug level. Message may not actually be written if this logger has been used too
     * recently.
     *
     * @param logMarker the marker to use
     * @param message   the message to write
     * @param varargs   optional arguments to pass to the log4j logger
     */
    public void debug(
            @NonNull final Marker logMarker, @NonNull final String message, @Nullable final Object... varargs) {

        log(Level.DEBUG, logMarker, message, varargs);
    }

    /**
     * Write a message to the log at trace level. Message may not actually be written if this logger has been used too
     * recently.
     *
     * @param logMarker the marker to use
     * @param message   the message to write
     * @param varargs   optional arguments to pass to the log4j logger
     */
    public void trace(
            @NonNull final Marker logMarker, @NonNull final String message, @Nullable final Object... varargs) {

        log(Level.TRACE, logMarker, message, varargs);
    }

    /**
     * Write a message to the log at info level. Message may not actually be written if this logger has been used too
     * recently.
     *
     * @param logMarker the marker to use
     * @param message   the message to write
     * @param varargs   optional arguments to pass to the log4j logger
     */
    public void info(
            @NonNull final Marker logMarker, @NonNull final String message, @Nullable final Object... varargs) {

        log(Level.INFO, logMarker, message, varargs);
    }

    /**
     * Write a message to the log at warn level. Message may not actually be written if this logger has been used too
     * recently.
     *
     * @param logMarker the marker to use
     * @param message   the message to write
     * @param varargs   optional arguments to pass to the log4j logger
     */
    public void warn(
            @NonNull final Marker logMarker, @NonNull final String message, @Nullable final Object... varargs) {

        log(Level.WARN, logMarker, message, varargs);
    }

    /**
     * Write a message to the log at error level. Message may not actually be written if this logger has been used too
     * recently.
     *
     * @param logMarker the marker to use
     * @param message   the message to write
     * @param varargs   optional arguments to pass to the log4j logger
     */
    public void error(
            @NonNull final Marker logMarker, @NonNull final String message, @Nullable final Object... varargs) {

        log(Level.ERROR, logMarker, message, varargs);
    }

    /**
     * Write a message to the log at fatal level. Message may not actually be written if this logger has been used too
     * recently.
     *
     * @param logMarker the marker to use
     * @param message   the message to write
     * @param varargs   optional arguments to pass to the log4j logger
     */
    public void fatal(
            @NonNull final Marker logMarker, @NonNull final String message, @Nullable final Object... varargs) {

        log(Level.FATAL, logMarker, message, varargs);
    }
}
