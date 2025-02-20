// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.test.fixtures.util;

/**
 * Convenience methods for creating exceptions with stacktrace as big as requested
 */
public class Throwables {

    public static final String METHOD_SIGNATURE_PATTERN =
            "\\tat " + Throwables.class.getName().replace(".", "\\.") + "\\.createThrowableWithDeepCause\\("
                    + Throwables.class.getSimpleName() + "\\.java:\\d+\\)";
    public static final String CAUSE_METHOD_SIGNATURE_PATTERN =
            "\\tat " + Throwables.class.getName().replace(".", "\\.") + "\\.createDeepThrowable\\("
                    + Throwables.class.getSimpleName() + "\\.java:\\d+\\)";

    private Throwables() {}

    /**
     * Creates a throwable with a {@code myDepth} stacktrace call and with cause having {@code causeDepth} nested
     * exceptions.
     */
    public static Throwable createThrowableWithDeepCause(final int myDepth, final int causeDepth) {
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
    public static Throwable createDeepThrowable(final int depth) {
        if (depth <= 0) {
            return new RuntimeException("test");
        }
        return createDeepThrowable(depth - 1);
    }
}
