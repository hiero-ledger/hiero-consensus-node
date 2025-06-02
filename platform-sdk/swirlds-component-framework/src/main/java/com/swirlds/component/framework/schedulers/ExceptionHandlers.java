package com.swirlds.component.framework.schedulers;

import java.lang.Thread.UncaughtExceptionHandler;

public class ExceptionHandlers {
    public static final UncaughtExceptionHandler RETHROW_UNCAUGHT_EXCEPTION = ExceptionHandlers::rethrowException;

    public static final UncaughtExceptionHandler NOOP_UNCAUGHT_EXCEPTION = ExceptionHandlers::ignoreException;

    private static void rethrowException(final Thread thread, final Throwable exception) {
        if (exception instanceof final RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw new RuntimeException("Uncaught exception in deterministic task scheduler", exception);
    }

    private static void ignoreException(final Thread thread, final Throwable exception) {
        // No-op uncaught exception handler
    }
}
