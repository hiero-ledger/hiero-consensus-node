// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.health;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.platform.health.entropy.OSEntropyCheck;
import org.junit.jupiter.api.Test;

class OSEntropyCheckTests {

    /**
     * All systems this test runs on should have an entropy generator, so the test should always pass
     */
    @Test
    void basicTest() {
        final OSEntropyCheck.Report report =
                assertDoesNotThrow(() -> OSEntropyCheck.execute(), "Check should not throw");
        assertTrue(report.success(), "Check should succeed");
        assertNotNull(report.elapsedNanos(), "Elapsed nanos should not be null");
        assertTrue(report.elapsedNanos() > 0, "Elapsed nanos should have a positive value");
        assertNotNull(report.randomLong(), "A random long should have been generated");
    }
}
