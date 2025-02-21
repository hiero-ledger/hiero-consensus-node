// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.health;

import static com.swirlds.common.formatting.StringFormattingUtils.addLine;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;

import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Utility methods for performing OS health checks
 */
public final class OSHealthChecker {

    private static final Logger logger = LogManager.getLogger(OSHealthChecker.class);

    private OSHealthChecker() {}

    /**
     * Performs the provided OS health checks and logs a report. If any checks fail, the report is logged at a warning.
     * If all checks pass, the report is logged at info.
     *
     * @param osHealthConfig
     * 		configuration containing values for OS health checks
     */
    public static void performOSHealthChecks(
            final OSHealthCheckConfig osHealthConfig, final Iterable<OSHealthCheck> healthChecks) {

        final StringBuilder sb = new StringBuilder();

        boolean allPassed = true;
        final long start = System.nanoTime();
        for (final OSHealthCheck check : healthChecks) {
            allPassed &= check.performCheckAndReport(sb, osHealthConfig);
        }
        final long end = System.nanoTime();

        sb.append(System.lineSeparator());
        addLine(
                sb,
                String.format(
                        "OS Health Check Report - Complete (took %d ms)", TimeUnit.NANOSECONDS.toMillis(end - start)));

        if (allPassed) {
            logger.info(STARTUP.getMarker(), sb);
        } else {
            logger.warn(STARTUP.getMarker(), sb);
        }
    }
}
