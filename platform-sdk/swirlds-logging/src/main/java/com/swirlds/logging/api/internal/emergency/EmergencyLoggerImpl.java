// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.api.internal.emergency;

import com.swirlds.logging.api.Level;
import com.swirlds.logging.api.extensions.emergency.EmergencyLogger;
import com.swirlds.logging.api.extensions.event.LogEvent;
import com.swirlds.logging.api.extensions.event.LogEventFactory;
import com.swirlds.logging.api.internal.event.SimpleLogEventFactory;
import com.swirlds.logging.api.internal.format.FormattedLinePrinter;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.PrintStream;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * A {@link EmergencyLogger} implementation that is used as a LAST RESORT when no other logger is available. It is
 * important that this logger does not depend on any other logger implementation and that it does not throw exceptions.
 * Next to that the logger should try to somehow log the message even in a broken system.
 * <p>
 * The logger is defined as a singleton.
 */
public class EmergencyLoggerImpl implements EmergencyLogger {

    private static final class InstanceHolder {
        /**
         * The singleton instance of the logger.
         */
        private static final EmergencyLoggerImpl INSTANCE = new EmergencyLoggerImpl();
    }

    /**
     * The name of the emergency logger.
     */
    private static final String EMERGENCY_LOGGER_NAME = "EMERGENCY-LOGGER";

    /**
     * The size of the queue that is used to store the log events.
     */
    private static final int LOG_EVENT_QUEUE_SIZE = 1000;

    /**
     * The name of the system property that defines the level of the logger.
     */
    private static final String LEVEL_PROPERTY_NAME = "com.swirlds.logging.emergency.level";

    public static final Level DEFAULT_LEVEL = Level.DEBUG;

    /**
     * The level that is supported by the logger.
     */
    private final AtomicReference<Level> supportedLevel;

    /**
     * The queue that is used to store the log events. Once the real logging system is available the events can be taken
     * by the logging system and logged.
     */
    private final ArrayBlockingQueue<LogEvent> logEvents;

    /**
     * A lock that is used to add log events to the queue.
     */
    private final Lock logEventsAddLock;

    /**
     * A thread local that is used to prevent recursion. This can happen when the logger is used in a broken system.
     */
    private final ThreadLocal<Boolean> recursionGuard;

    /**
     * The factory that is used to create log events.
     */
    private final LogEventFactory logEventFactory;

    private final Lock handleLock;

    private final AtomicReference<FormattedLinePrinter> linePrinter = new AtomicReference<>();

    /**
     * Creates the singleton instance of the logger.
     */
    private EmergencyLoggerImpl() {
        this.logEvents = new ArrayBlockingQueue<>(LOG_EVENT_QUEUE_SIZE);
        recursionGuard = new ThreadLocal<>();
        supportedLevel = new AtomicReference<>();
        logEventsAddLock = new ReentrantLock();
        logEventFactory = new SimpleLogEventFactory();
        handleLock = new ReentrantLock();
    }

    /**
     * Returns the level based on a possible system property.
     *
     * @return the level based on a possible system property
     */
    @NonNull
    private static Level getSupportedLevelFromSystemProperties() {
        final String property = System.getProperty(LEVEL_PROPERTY_NAME);
        if (property == null) {
            return DEFAULT_LEVEL;
        } else if (Objects.equals(property.toUpperCase(), Level.TRACE.name())) {
            return Level.TRACE;
        } else if (Objects.equals(property.toUpperCase(), Level.DEBUG.name())) {
            return Level.DEBUG;
        } else if (Objects.equals(property.toUpperCase(), Level.INFO.name())) {
            return Level.INFO;
        } else if (Objects.equals(property.toUpperCase(), Level.WARN.name())) {
            return Level.WARN;
        } else if (Objects.equals(property.toUpperCase(), Level.ERROR.name())) {
            return Level.ERROR;
        } else {
            return Level.TRACE;
        }
    }

    @Override
    public void logNPE(@NonNull final String nameOfNullParam) {
        log(
                Level.ERROR,
                "Null parameter: " + nameOfNullParam,
                new NullPointerException("Null parameter: " + nameOfNullParam));
    }

    @Override
    public void log(@NonNull Level level, @NonNull String message, @Nullable Throwable thrown) {
        log(logEventFactory.createLogEvent(level, EMERGENCY_LOGGER_NAME, message, thrown));
    }

    @Override
    public void log(@NonNull LogEvent event) {
        if (event == null) {
            logNPE("event");
            return;
        }
        if (isLoggable(event.level())) {
            callGuarded(event, () -> handle(event));
        }
    }

    /**
     * Checks if the given level is loggable by the logger.
     *
     * @param level the level to check
     * @return true if the level is loggable, false otherwise
     */
    private boolean isLoggable(@NonNull Level level) {
        if (level == null) {
            logNPE("level");
            return true;
        }
        if (supportedLevel.get() == null) {
            supportedLevel.set(getSupportedLevelFromSystemProperties());
        }
        final Level internalSupportedLevel = supportedLevel.get();
        if (internalSupportedLevel == null) {
            return true;
        }
        return internalSupportedLevel.enabledLoggingOfLevel(level);
    }

    /**
     * A method that is used to call any given {@link Runnable} in a guarded way. This includes exception handling and a
     * recursion check. In case of a problem the method tries to at least log the given fallback log event.
     *
     * @param fallbackLogEvent the fallback log event that should be logged when the logger is broken.
     * @param task             the task that should be called
     */
    private void callGuarded(@NonNull final LogEvent fallbackLogEvent, @NonNull final Runnable task) {
        callGuarded(fallbackLogEvent, null, () -> {
            task.run();
            return null;
        });
    }

    /**
     * A method that is used to call any given {@link Supplier} in a guarded way. This includes exception handling and a
     * recursion check. In case of a problem the method tries to at least log the given fallback log event.
     *
     * @param fallbackLogEvent the fallback log event that should be logged when the logger is broken.
     * @param fallbackValue    the fallback value that should be returned when the logger is broken.
     * @param supplier         the supplier that should be called
     * @param <T>              the type of the result
     * @return the result of the supplier
     */
    @Nullable
    private <T> T callGuarded(
            @Nullable final LogEvent fallbackLogEvent, @Nullable T fallbackValue, @NonNull final Supplier<T> supplier) {
        final Boolean guard = recursionGuard.get();
        if (guard != null && guard) {
            final LogEvent logEvent = logEventFactory.createLogEvent(
                    Level.ERROR,
                    EMERGENCY_LOGGER_NAME,
                    "Recursion in Emergency logger",
                    new IllegalStateException("Recursion in Emergency logger"));
            handle(logEvent);
            if (fallbackLogEvent != null) {
                handle(fallbackLogEvent);
            }
            return fallbackValue;
        } else {
            recursionGuard.set(true);
            try {
                return supplier.get();
            } catch (final Throwable t) {
                final LogEvent logEvent = logEventFactory.createLogEvent(
                        Level.ERROR, EMERGENCY_LOGGER_NAME, "Error in Emergency logger", t);
                handle(logEvent);
                if (fallbackLogEvent != null) {
                    handle(fallbackLogEvent);
                }
                return fallbackValue;
            } finally {
                recursionGuard.set(false);
            }
        }
    }

    /**
     * Handles the given log event by trying to print the message to the console and adding it to the queue.
     *
     * @param logEvent the log event that should be handled
     */
    private void handle(@NonNull final LogEvent logEvent) {
        if (logEvent == null) {
            logNPE("logEvent");
            return;
        }
        final PrintStream printStream = Optional.ofNullable(System.err).orElse(System.out);
        if (printStream != null) {
            handleLock.lock();
            try {
                final StringBuilder stringBuilder = new StringBuilder();
                getLinePrinter().print(stringBuilder, logEvent);
                printStream.print(stringBuilder);
            } finally {
                handleLock.unlock();
            }
            printStream.flush();
        } else {
            // LET'S HOPE THAT THIS NEVER HAPPENS...
        }

        logEventsAddLock.lock();
        try {
            if (logEvents.remainingCapacity() == 0) {
                while (logEvents.remainingCapacity() <= 100) {
                    logEvents.remove();
                }
            }
            logEvents.add(logEvent);
        } finally {
            logEventsAddLock.unlock();
        }
    }

    /**
     * Gets with lazy initialization the field an instance of {@link FormattedLinePrinter}
     * @return a {@link FormattedLinePrinter} instance
     */
    private @NonNull FormattedLinePrinter getLinePrinter() {
        if (linePrinter.get() == null) {
            linePrinter.compareAndSet(null, new FormattedLinePrinter(false));
        }
        return linePrinter.get();
    }

    /**
     * Returns the list of logged events and clears the list.
     *
     * @return the list of logged events
     */
    @NonNull
    public List<LogEvent> publishLoggedEvents() {
        List<LogEvent> result = List.copyOf(logEvents);
        logEvents.clear();
        return result;
    }

    /**
     * Returns the instance of the logger.
     *
     * @return the instance of the logger
     */
    @NonNull
    public static EmergencyLoggerImpl getInstance() {
        return InstanceHolder.INSTANCE;
    }
}
