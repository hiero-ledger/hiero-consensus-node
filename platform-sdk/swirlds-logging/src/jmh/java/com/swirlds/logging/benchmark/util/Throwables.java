// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.benchmark.util;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Convenience methods for creating exceptions with stacktrace as big as requested
 */
public class Throwables {
    public static final Throwable THROWABLE = createThrowable();

    public static final Throwable DEEP_THROWABLE = createThrowableWithDeepCause(20, 20);

    private Throwables() {}

    /**
     * Creates a throwable with a {@code myDepth} stacktrace call and with cause having {@code causeDepth} nested
     * exceptions.
     */
    public static @NonNull Throwable createThrowableWithDeepCause(final int myDepth, final int causeDepth) {
        if (myDepth > 0) {
            return createThrowableWithDeepCause(myDepth - 1, causeDepth);
        }
        try {
            throw createDeepThrowable(causeDepth);
        } catch (Throwable t) {
            return new RuntimeException("test", t);
        }
    }

    /**
     * Creates a throwable with cause having {@code depth} nested exceptions.
     */
    public static @NonNull Throwable createDeepThrowable(final int depth) {
        if (depth <= 0) {
            return new RuntimeException("test");
        }
        return createDeepThrowable(depth - 1);
    }

    /**
     * Creates a throwable.
     */
    public static @NonNull Throwable createThrowable() {
        return new RuntimeException("test");
    }
}
