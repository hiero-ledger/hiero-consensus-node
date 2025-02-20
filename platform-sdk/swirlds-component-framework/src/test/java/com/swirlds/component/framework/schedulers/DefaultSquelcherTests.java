// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.schedulers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.component.framework.schedulers.internal.DefaultSquelcher;
import com.swirlds.component.framework.schedulers.internal.Squelcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link DefaultSquelcher} class.
 */
class DefaultSquelcherTests {
    @Test
    @DisplayName("Standard operation")
    void standardOperation() {
        final Squelcher defaultSquelcher = new DefaultSquelcher();
        assertFalse(defaultSquelcher.shouldSquelch());

        defaultSquelcher.startSquelching();
        assertTrue(defaultSquelcher.shouldSquelch());

        defaultSquelcher.stopSquelching();
        assertFalse(defaultSquelcher.shouldSquelch());
    }

    @Test
    @DisplayName("Illegal state handling")
    void illegalStateHandling() {
        final Squelcher defaultSquelcher = new DefaultSquelcher();
        assertThrows(IllegalStateException.class, defaultSquelcher::stopSquelching);

        defaultSquelcher.startSquelching();
        assertThrows(IllegalStateException.class, defaultSquelcher::startSquelching);
    }
}
