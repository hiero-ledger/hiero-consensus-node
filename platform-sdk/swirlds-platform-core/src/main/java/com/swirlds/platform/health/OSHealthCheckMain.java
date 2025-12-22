// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.health;

import static com.swirlds.platform.health.filesystem.OSFileSystemCheck.printReport;

import com.swirlds.platform.health.clock.OSClockSourceSpeedCheck;
import com.swirlds.platform.health.entropy.OSEntropyCheck;
import java.nio.file.Path;

/**
 * Command line tool for running the OS health checks and printing the results.
 */
public final class OSHealthCheckMain {

    private OSHealthCheckMain() {}

    /**
     * Prints the results of the OS health checks using system defaults
     */
    public static void main(final String[] args) {
        System.out.println("OBSOLETE! Please use pcli.sh health-check instead");
        OSClockSourceSpeedCheck.printReport();
        OSEntropyCheck.printReport();
        if (args.length > 0) {
            printReport(Path.of(args[0]));
        }
    }
}
