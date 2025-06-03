// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.schedulers;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.Thread.UncaughtExceptionHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ExceptionHandlers {
    public static final UncaughtExceptionHandler RETHROW_UNCAUGHT_EXCEPTION = ExceptionHandlers::rethrowException;

    public static final UncaughtExceptionHandler NOOP_UNCAUGHT_EXCEPTION = ExceptionHandlers::ignoreException;

    public static UncaughtExceptionHandler defaultExceptionHandler(@NonNull final String name) {
        return new DefaultUncaughtExceptionHandler(name);
    }

    private static void rethrowException(final Thread thread, final Throwable exception) {
        if (exception instanceof final RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw new RuntimeException("Uncaught exception in wiring", exception);
    }

    private static void ignoreException(final Thread thread, final Throwable exception) {
        // No-op uncaught exception handler
    }

    private record DefaultUncaughtExceptionHandler(@NonNull String name) implements UncaughtExceptionHandler {
        private static final Logger logger = LogManager.getLogger(DefaultUncaughtExceptionHandler.class);

        @Override
        public void uncaughtException(final Thread thread, final Throwable exception) {
            logger.error(EXCEPTION.getMarker(), "Uncaught exception in {}", name, exception);
        }
    }
}
