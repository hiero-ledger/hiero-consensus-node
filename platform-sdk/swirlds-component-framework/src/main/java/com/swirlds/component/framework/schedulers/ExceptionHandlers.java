// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.schedulers;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.Thread.UncaughtExceptionHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Utility class for exception handlers in the wiring framework.
 */
public final class ExceptionHandlers {
    /**
     * Uncaught exception handler that rethrows the exception as a RuntimeException.
     * This is useful for testing and debugging purposes, as it allows the exception to propagate.
     */
    public static final UncaughtExceptionHandler RETHROW_UNCAUGHT_EXCEPTION = ExceptionHandlers::rethrowException;

    /**
     * Uncaught exception handler that ignores the exception.
     * This is useful when you want to suppress exceptions without any logging or handling.
     */
    public static final UncaughtExceptionHandler NOOP_UNCAUGHT_EXCEPTION = ExceptionHandlers::ignoreException;

    /**
     * Creates a default uncaught exception handler that logs the exception.
     *
     * @param name the name of the component for logging purposes
     * @return a new instance of {@link UncaughtExceptionHandler} that logs exceptions
     */
    @NonNull
    public static UncaughtExceptionHandler defaultExceptionHandler(@NonNull final String name) {
        return new DefaultUncaughtExceptionHandler(name);
    }

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private ExceptionHandlers() {
        // Prevent instantiation
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /**
     * Rethrows the given exception as a RuntimeException.
     * If the exception is already a RuntimeException, it is thrown directly.
     * Otherwise, it wraps the exception in a RuntimeException and throws it.
     *
     * @param thread    the thread that encountered the exception
     * @param exception the exception to rethrow
     */
    private static void rethrowException(@NonNull final Thread thread, @NonNull final Throwable exception) {
        if (exception instanceof final RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw new RuntimeException("Uncaught exception in wiring", exception);
    }

    /**
     * A no-op uncaught exception handler that ignores the exception.
     * This is useful when you want to suppress exceptions without any logging or handling.
     *
     * @param thread    the thread that encountered the exception
     * @param exception the exception to ignore
     */
    private static void ignoreException(@NonNull final Thread thread, @NonNull final Throwable exception) {
        // No-op uncaught exception handler
    }

    /**
     * Default implementation of an uncaught exception handler that logs the exception.
     */
    private record DefaultUncaughtExceptionHandler(@NonNull String name) implements UncaughtExceptionHandler {
        private static final Logger logger = LogManager.getLogger(DefaultUncaughtExceptionHandler.class);

        @Override
        public void uncaughtException(final Thread thread, final Throwable exception) {
            logger.error(EXCEPTION.getMarker(), "Uncaught exception in {}", name, exception);
        }
    }
}
