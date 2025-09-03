package com.swirlds.platform.health;

import com.swirlds.base.units.UnitConstants;
import com.swirlds.platform.health.clock.OSClockSourceSpeedCheck;
import com.swirlds.platform.health.entropy.OSEntropyCheck;
import com.swirlds.platform.health.filesystem.OSFileSystemCheck;
import java.nio.file.Path;

public class HealthReportUtils {
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
