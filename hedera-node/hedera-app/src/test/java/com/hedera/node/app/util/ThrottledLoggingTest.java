// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ThrottledLoggingTest {
    private final ThrottledLogging subject = new ThrottledLogging();

    @Test
    void suppressesDuplicateThrowablesUntilReset() {
        assertTrue(subject.shouldLog(failure("boom", null)));
        assertFalse(subject.shouldLog(failure("boom", null)));

        subject.reset();

        assertTrue(subject.shouldLog(failure("boom", null)));
    }

    @Test
    void logsDifferentThrowableTypesIndependently() {
        assertTrue(subject.shouldLog(new NullPointerException("boom")));
        assertTrue(subject.shouldLog(new IllegalStateException("boom")));
    }

    @Test
    void logsDifferentCauseChainsIndependently() {
        assertTrue(subject.shouldLog(failure("boom", null)));
        assertTrue(subject.shouldLog(failure("boom", new IllegalArgumentException("cause"))));
    }

    private static IllegalStateException failure(final String message, final Throwable cause) {
        return new IllegalStateException(message, cause);
    }
}
