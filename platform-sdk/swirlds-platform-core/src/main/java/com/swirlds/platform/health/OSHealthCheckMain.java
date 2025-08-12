// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.health;

import static com.swirlds.platform.cli.HealthCheckCommand.printOSClockSpeedReport;
import static com.swirlds.platform.cli.HealthCheckCommand.printOSEntropyReport;
import static com.swirlds.platform.cli.HealthCheckCommand.printOSFileSystemReport;

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
        printOSClockSpeedReport();
        printOSEntropyReport();
        if (args.length > 0) {
            printOSFileSystemReport(Path.of(args[0]));
        }
    }
}
