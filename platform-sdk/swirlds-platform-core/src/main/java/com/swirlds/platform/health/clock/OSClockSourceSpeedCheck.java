// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.health.clock;

import java.time.Instant;

/**
 * Checks the speed of the operating system's clock source
 */
public final class OSClockSourceSpeedCheck {

    private OSClockSourceSpeedCheck() {}

    /**
     * Get the speed of the {@link Instant#now()} operation by counting the number of calls achieved in 1 second.
     *
     * @return the clock source speed
     */
    public static Report execute() {
        int numCalls = 0;

        // count the number of calls achieved in 1 second
        final Instant deadline = Instant.now().plusSeconds(1);
        while (Instant.now().compareTo(deadline) < 0) {
            numCalls++;
        }

        return new Report(numCalls);
    }

    /**
     * Contains data about the speed of the OS clock source
     *
     * @param callsPerSec
     * 		the calls per second achieved
     */
    public record Report(long callsPerSec) {

        private static final String NAME = "Clock Source Speed Check";

        /**
         * @return the name of the check this report applies to
         */
        public static String name() {
            return NAME;
        }
    }
}
