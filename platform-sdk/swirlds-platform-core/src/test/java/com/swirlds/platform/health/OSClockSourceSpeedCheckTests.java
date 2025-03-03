// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.health;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.platform.health.clock.OSClockSourceSpeedCheck;
import org.junit.jupiter.api.Test;

class OSClockSourceSpeedCheckTests {

    @Test
    void basicTest() {
        OSClockSourceSpeedCheck.Report report =
                assertDoesNotThrow(OSClockSourceSpeedCheck::execute, "Check should not throw");
        assertTrue(report.callsPerSec() > 0, "Calls per second should be positive");
    }
}
