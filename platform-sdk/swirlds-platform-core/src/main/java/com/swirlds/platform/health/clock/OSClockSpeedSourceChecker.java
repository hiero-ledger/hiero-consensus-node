// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.health.clock;

import static com.swirlds.common.formatting.StringFormattingUtils.addLine;
import static com.swirlds.platform.health.OSHealthCheckUtils.reportHeader;

import com.swirlds.platform.health.OSHealthCheckConfig;

/**
 * Utility class that performs OS clock source speed checks and writes the report to a {@link StringBuilder}.
 */
public final class OSClockSpeedSourceChecker {

    private OSClockSpeedSourceChecker() {}

    public static boolean performClockSourceSpeedCheck(
            final StringBuilder sb, final OSHealthCheckConfig osHealthConfig) {
        final OSClockSourceSpeedCheck.Report clockSourceSpeed = OSClockSourceSpeedCheck.execute();
        return appendReport(sb, clockSourceSpeed, osHealthConfig.minClockCallsPerSec());
    }

    /**
     * Append the results of the clock speed check report to the string builder.
     *
     * @param sb
     * 		the string builder to append to
     * @param clockReport
     * 		the clock source speed check report
     * @param minClockCallsPerSec
     * 		the minimum number of clock calls per second required for the check to pass
     * @return {@code true} if the check passed, {@code false} otherwise
     */
    private static boolean appendReport(
            final StringBuilder sb, OSClockSourceSpeedCheck.Report clockReport, final long minClockCallsPerSec) {
        if (clockReport.callsPerSec() < minClockCallsPerSec) {
            reportHeader(sb, OSClockSourceSpeedCheck.Report.name(), false);
            addLine(
                    sb,
                    String.format(
                            "OS clock source is too slow. Calls/sec Minimum: %d, Achieved: %d",
                            minClockCallsPerSec, clockReport.callsPerSec()));
            return false;
        }
        reportHeader(sb, OSClockSourceSpeedCheck.Report.name(), true);
        addLine(sb, clockReport.toString());
        return true;
    }
}
