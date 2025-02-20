// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.utility;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.cli.logging.PlatformStatusLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PlatformStatusLog Tests
 */
class PlatformStatusLogTests {
    public static final String testString =
            """
                    Platform spent 441.0 ms in STARTING_UP. Now in REPLAYING_EVENTS {"oldStatus":"STARTING_UP","newStatus":"REPLAYING_EVENTS"} [com.swirlds.logging.payloads.PlatformStatusPayload]
                    """;

    @Test
    @DisplayName("Test splitting a status log")
    void splitStatusLog() {
        final PlatformStatusLog statusLog = new PlatformStatusLog(testString.trim());

        assertEquals("STARTING_UP", statusLog.getPreviousStatus());
        assertEquals("REPLAYING_EVENTS", statusLog.getNewStatus());
        assertEquals("441.0 ms", statusLog.getDuration());
    }
}
