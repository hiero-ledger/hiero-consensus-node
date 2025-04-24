// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.test.fixtures.util;

/**
 * Convenience methods for creating exceptions with stacktrace as big as requested
 */
public class Throwables {

    private static String methodSignaturePatternModulePath() {
        return "\\tat " + Throwables.class.getModule().getName() + "@"
                + Throwables.class.getModule().getDescriptor().version().orElseThrow() + "/"
                + Throwables.class.getName().replace(".", "\\.") + "\\.createThrowableWithDeepCause\\("
                + Throwables.class.getSimpleName() + "\\.java:\\d+\\)";
    }

    private static String methodSignaturePatternClasspath() {
        return "\\tat " + Throwables.class.getName().replace(".", "\\.") + "\\.createThrowableWithDeepCause\\("
                + Throwables.class.getSimpleName() + "\\.java:\\d+\\)";
    }

    private static String causeMethodSignaturePatternModulePath() {
        return "\\tat " + Throwables.class.getModule().getName() + "@"
                + Throwables.class.getModule().getDescriptor().version().orElseThrow() + "/"
                + Throwables.class.getName().replace(".", "\\.") + "\\.createDeepThrowable\\("
                + Throwables.class.getSimpleName() + "\\.java:\\d+\\)";
    }

    private static String causeMethodSignaturePatternClasspath() {
        return "\\tat " + Throwables.class.getName().replace(".", "\\.") + "\\.createDeepThrowable\\("
                + Throwables.class.getSimpleName() + "\\.java:\\d+\\)";
    }

    public static String METHOD_SIGNATURE_PATTERN = Throwables.class.getModule().isNamed()
            ? methodSignaturePatternModulePath()
            : methodSignaturePatternClasspath();

    public static String CAUSE_METHOD_SIGNATURE_PATTERN =
            Throwables.class.getModule().isNamed()
                    ? causeMethodSignaturePatternModulePath()
                    : causeMethodSignaturePatternClasspath();

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
