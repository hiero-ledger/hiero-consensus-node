// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.test.fixtures;

import com.swirlds.logging.api.Level;
import com.swirlds.logging.api.extensions.event.LogEvent;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * A mirror of the logging system that can be used to check the logging events that were generated during a test. A
 * mirror is automatically cleared before and after a test. By doing so a mirror always contains log events of the
 * current test. To use the class a test must be annotated with {@link WithLoggingMirror}.
 *
 * @see WithLoggingMirror
 */
public interface LoggingMirror extends AutoCloseable {

    /**
     * Returns a mirror that only contains log events were the logEventPredicate evaluates to true.
     *
     * @param logEventPredicate the level to filter by
     * @return a mirror that only contains log events with the given level
     */
    @NonNull
    LoggingMirror filter(@NonNull Predicate<LogEvent> logEventPredicate);

    /**
     * Returns a mirror that only contains log events with the given logger name (based on the class name).
     *
     * @param clazz the class to filter by
     * @return a mirror that only contains log events with the given logger name
     */
    default LoggingMirror filterByLogger(@NonNull final Class<?> clazz) {
        return filterByLogger(clazz.getName());
    }

    /**
     * Returns a list of all log events that were generated during a test.
     *
     * @return a list of all log events that were generated during a test
     */
    List<LogEvent> getEvents();

    /**
     * Returns the number of log events that were generated during a test.
     *
     * @return the number of log events that were generated during a test
     */
    default int getEventCount() {
        return getEvents().size();
    }

    /**
     * Returns a mirror that only contains log events with the given level.
     *
     * @param level the level to filter by
     * @return a mirror that only contains log events with the given level
     */
    @NonNull
    default LoggingMirror filterByLevel(@NonNull final Level level) {
        Objects.requireNonNull(level, "level must not be null");
        Predicate<LogEvent> filter = event -> event.level() == level;
        return filter(filter);
    }

    /**
     * Returns a mirror that only contains log events above the given level.
     *
     * @param level the level to filter by
     * @return a mirror that only contains log events with the given level
     */
    @NonNull
    default LoggingMirror filterAboveLevel(@NonNull final Level level) {
        Objects.requireNonNull(level, "level must not be null");
        final Predicate<LogEvent> filter =
                event -> level.ordinal() >= event.level().ordinal();
        return filter(filter);
    }

    /**
     * Returns a mirror that only contains log events with the given context.
     *
     * @param key   the key of the context
     * @param value the value of the context
     * @return a mirror that only contains log events with the given context
     */
    @NonNull
    default LoggingMirror filterByContext(@NonNull final String key, @NonNull final String value) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
        final Predicate<LogEvent> filter = event ->
                event.context().containsKey(key) && event.context().get(key).equals(value);
        return filter(filter);
    }

    /**
     * Returns a mirror that only contains log events with the given thread.
     *
     * @param threadName the name of the thread
     * @return a mirror that only contains log events with the given thread
     */
    @NonNull
    default LoggingMirror filterByThread(@NonNull final String threadName) {
        Objects.requireNonNull(threadName, "threadName must not be null");
        final Predicate<LogEvent> filter = event -> Objects.equals(event.threadName(), threadName);
        return filter(filter);
    }

    /**
     * Returns a mirror that only contains log events with the current thread.
     *
     * @return a mirror that only contains log events with the current thread
     */
    @NonNull
    default LoggingMirror filterByCurrentThread() {
        return filterByThread(Thread.currentThread().getName());
    }

    /**
     * Returns a mirror that only contains log events with the given logger name.
     *
     * @param loggerName the logger name to filter by
     * @return a mirror that only contains log events with the given logger name
     */
    @NonNull
    default LoggingMirror filterByLogger(@NonNull final String loggerName) {
        Objects.requireNonNull(loggerName, "loggerName must not be null");
        final Predicate<LogEvent> filter = event -> event.loggerName().startsWith(loggerName);
        return filter(filter);
    }
}
