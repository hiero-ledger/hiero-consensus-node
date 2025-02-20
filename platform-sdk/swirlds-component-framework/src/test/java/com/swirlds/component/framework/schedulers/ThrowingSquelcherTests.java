// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.schedulers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.component.framework.schedulers.internal.Squelcher;
import com.swirlds.component.framework.schedulers.internal.ThrowingSquelcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link ThrowingSquelcher} class.
 */
class ThrowingSquelcherTests {
    @Test
    @DisplayName("Standard operation")
    void standardOperation() {
        final Squelcher throwingSquelcher = new ThrowingSquelcher();
        assertFalse(throwingSquelcher.shouldSquelch());

        assertThrows(UnsupportedOperationException.class, throwingSquelcher::startSquelching);
        assertThrows(UnsupportedOperationException.class, throwingSquelcher::stopSquelching);

        assertFalse(throwingSquelcher.shouldSquelch());
    }
}
