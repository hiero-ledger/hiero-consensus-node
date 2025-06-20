// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fixtures;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.node.app.spi.workflows.PreCheckException;
import edu.umd.cs.findbugs.annotations.NonNull;

/** A convenience class for testing with Hedera specific assertions */
public final class Assertions {
    /**
     * Asserts that the given {@code runnable}, when run, throws a {@link PreCheckException} with the given
     * expected {@link ResponseCodeEnum}.
     *
     * @param runnable The runnable which will throw a {@link PreCheckException}.
     * @param expected The expected status code of the exception
     */
    public static void assertThrowsPreCheck(
            @NonNull final PreCheckRunnable runnable, @NonNull final ResponseCodeEnum expected) {
        try {
            runnable.run();
            throw new AssertionError("Expected " + expected + " but no exception was thrown", null);
        } catch (final PreCheckException actual) {
            if (!actual.responseCode().equals(expected)) {
                throw new AssertionError("Expected " + expected + " but got " + actual, actual);
            }
        }
    }

    /** A {@link Runnable} like interface that throws the checked {@link PreCheckException}. */
    @FunctionalInterface
    public interface PreCheckRunnable {
        void run() throws PreCheckException;
    }
}
