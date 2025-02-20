// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.exceptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

public final class ExceptionAssertions {
    public static final String CAUSE_MESSAGE = "cause";
    public static final Throwable CAUSE = new Exception(CAUSE_MESSAGE);
    public static final String MESSAGE = "message";

    private ExceptionAssertions() {}

    public static void assertExceptionSame(final Exception e, final String message, final Throwable cause) {
        assertEquals(message, e.getMessage(), "Expected message does not equal the actual message!");
        assertSame(cause, e.getCause(), "The cause is not the instance that was expected!");
    }

    public static void assertExceptionContains(final Exception e, final List<String> contains, final Throwable cause) {
        for (String s : contains) {
            assertTrue(e.getMessage().contains(s), "The message does not contain the expected strings!");
        }
        assertSame(cause, e.getCause(), "The cause is not the instance that was expected!");
    }
}
