// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.cli;

import com.swirlds.base.units.UnitConstants;
import com.swirlds.cli.PlatformCli;
import com.swirlds.cli.utility.AbstractCommand;
import com.swirlds.cli.utility.SubcommandOf;
import com.swirlds.platform.health.clock.OSClockSourceSpeedCheck;
import com.swirlds.platform.health.entropy.OSEntropyCheck;
import com.swirlds.platform.health.filesystem.OSFileSystemCheck;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import picocli.CommandLine;

@CommandLine.Command(
        name = "health-check",
        mixinStandardHelpOptions = true,
        description = "Executes basic OS health checks and reports results")
@SubcommandOf(PlatformCli.class)
public class HealthCheckCommand extends AbstractCommand {

    private String file;
    private int clockLimit;
    private double randomLimit;
    private double fileLimit;

    @CommandLine.Option(
            names = {"-f", "--file"},
            description = "Path to existing non-empty file on which read performance will be checked")
    private void setFile(@NonNull final String file) {
        this.file = file;
    }

    @CommandLine.Option(
            names = {"--clock-limit"},
            description = "Minimum amount of calls per second for clock check to pass")
    private void setClockLimit(final int clockLimit) {
        this.clockLimit = clockLimit;
    }

    @CommandLine.Option(
            names = {"--random-limit"},
            description = "Maximum time (in ms) for random entropy generator to count as a pass")
    private void setRandomLimit(final double randomLimit) {
        this.randomLimit = randomLimit;
    }

    @CommandLine.Option(
            names = {"--file-limit"},
            description = "Maximum time (in ms) for file read check to count as a pass")
    private void setFileLimit(final double fileLimit) {
        this.fileLimit = fileLimit;
    }

    @Override
    public Integer call() throws Exception {
        System.out.println(
                "Please be warned - all these statistics are printed for non-warmed JVM, so they are not representative of the real world performance");

        final long clockSpeed = printOSClockSpeedReport();
        final double randomSpeed = printOSEntropyReport();

        if (file != null) {
            final double fileSpeed = printOSFileSystemReport(Path.of(file));
            if (fileLimit > 0 && fileSpeed > fileLimit) {
                System.out.printf("File read time time too big, above limit of %f ms%n", fileLimit);
                return 3;
            }
        }

        if (clockLimit > 0 && clockSpeed < clockLimit) {
            System.out.printf("Clock speed not good enough, below limit of %d calls/sec%n", clockLimit);
            return 1;
        }

        if (randomLimit > 0 && randomSpeed > randomLimit) {
            System.out.printf("Random entropy time too big, above limit of %f ms%n", randomLimit);
            return 2;
        }

        return 0;
    }

    public static long printOSClockSpeedReport() {
        final OSClockSourceSpeedCheck.Report clockSpeedReport = OSClockSourceSpeedCheck.execute();
        System.out.printf("Average clock source speed: %d calls/sec%n", clockSpeedReport.callsPerSec());
        return clockSpeedReport.callsPerSec();
    }

    public static double printOSEntropyReport() {
        try {
            final OSEntropyCheck.Report randomSpeed = OSEntropyCheck.execute();
            if (randomSpeed.success()) {
                final double elapsedMillis = randomSpeed.elapsedNanos() * UnitConstants.NANOSECONDS_TO_MILLISECONDS;
                System.out.printf(
                        "First random number generation time: %d nanos (%s millis), generated long=%d%n",
                        randomSpeed.elapsedNanos(), elapsedMillis, randomSpeed.randomLong());
                return elapsedMillis;
            } else {
                System.out.println("Random number generation check failed due to timeout");
                return Double.POSITIVE_INFINITY;
            }
        } catch (InterruptedException e) {
            System.out.println("Thread interrupted while measuring the random number generation speed");
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    public static double printOSFileSystemReport(final Path fileToRead) {
        try {
            final OSFileSystemCheck.Report report = OSFileSystemCheck.execute(fileToRead);
            if (report.code() == OSFileSystemCheck.TestResultCode.SUCCESS) {
                final double elapsedMillis = report.readNanos() * UnitConstants.NANOSECONDS_TO_MILLISECONDS;
                System.out.printf(
                        "File system check, took %d nanos (%s millis) "
                                + "to open the file and read 1 byte (data=%s)%n",
                        report.readNanos(), elapsedMillis, report.data());
                return elapsedMillis;
            } else {
                if (report.exception() == null) {
                    System.out.printf("File system check failed. Reason: %s%n", report.code());
                } else {
                    System.out.printf(
                            "File system check failed with exception. Reason: %s%n%s%n",
                            report.code(), report.exception());
                }
                return Double.POSITIVE_INFINITY;
            }
        } catch (InterruptedException e) {
            System.out.println("Thread interrupted while checking the file system");
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
