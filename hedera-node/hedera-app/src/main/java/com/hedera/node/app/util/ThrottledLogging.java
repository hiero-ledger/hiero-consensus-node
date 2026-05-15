// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.util;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Suppresses repeated logs for equivalent throwables until reset.
 */
public final class ThrottledLogging {
    @Nullable
    private ThrowableFingerprint lastFingerprint;

    /**
     * Returns {@code true} if the given throwable differs from the last logged throwable fingerprint.
     *
     * @param throwable the throwable to evaluate
     * @return whether the throwable should be logged
     */
    public boolean shouldLog(@NonNull final Throwable throwable) {
        requireNonNull(throwable);
        final var fingerprint = ThrowableFingerprint.of(throwable);
        if (fingerprint.equals(lastFingerprint)) {
            return false;
        }
        lastFingerprint = fingerprint;
        return true;
    }

    /**
     * Clears the last logged throwable fingerprint.
     */
    public void reset() {
        lastFingerprint = null;
    }

    private record ThrowableFingerprint(
            @NonNull String exceptionType,
            @Nullable String message,
            @Nullable StackTraceElement topFrame,
            @Nullable ThrowableFingerprint cause) {
        private ThrowableFingerprint {
            requireNonNull(exceptionType);
        }

        @NonNull
        private static ThrowableFingerprint of(@NonNull final Throwable throwable) {
            requireNonNull(throwable);
            final var stackTrace = throwable.getStackTrace();
            final StackTraceElement topFrame = stackTrace.length > 0 ? stackTrace[0] : null;
            final var cause = throwable.getCause();
            return new ThrowableFingerprint(
                    throwable.getClass().getName(), throwable.getMessage(), topFrame, cause == null ? null : of(cause));
        }
    }
}
