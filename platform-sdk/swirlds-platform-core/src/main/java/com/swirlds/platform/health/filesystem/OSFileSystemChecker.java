// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.health.filesystem;

import static com.swirlds.common.formatting.StringFormattingUtils.addLine;
import static com.swirlds.platform.health.OSHealthCheckUtils.reportHeader;

import com.swirlds.base.units.UnitConstants;
import com.swirlds.platform.health.OSHealthCheckConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Performs file system checks and writes the report to a {@link StringBuilder}.
 */
public final class OSFileSystemChecker {

    private final Path configPath;

    /**
     * Construct a new {@link OSFileSystemChecker} instance.
     *
     * @param configPath the path to config.txt
     */
    public OSFileSystemChecker(@NonNull final Path configPath) {
        this.configPath = Objects.requireNonNull(configPath);
    }

    public boolean performFileSystemCheck(
            @NonNull final StringBuilder sb, @NonNull final OSHealthCheckConfig osHealthConfig) {
        Objects.requireNonNull(sb, "sb must not be null");
        Objects.requireNonNull(osHealthConfig, "osHealthConfig must not be null");

        try {
            final OSFileSystemCheck.Report fileSystemReport =
                    OSFileSystemCheck.execute(configPath, osHealthConfig.fileReadTimeoutMillis());
            return appendReport(sb, fileSystemReport, osHealthConfig.maxFileReadMillis());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Thread interrupted while performing OS file system check", e);
        }
    }

    /**
     * Append the results of the file system check report to the string builder.
     *
     * @param sb                the string builder to append to
     * @param fileSystemReport  the file system check report
     * @param maxFileReadMillis the maximum number of millis the file read may take before it is considered failed
     * @return {@code true} if the check passed, {@code false} otherwise
     */
    private boolean appendReport(
            final StringBuilder sb, final OSFileSystemCheck.Report fileSystemReport, final long maxFileReadMillis) {
        if (fileSystemReport.code() == OSFileSystemCheck.TestResultCode.SUCCESS) {
            final double readMillis = fileSystemReport.readNanos() * UnitConstants.NANOSECONDS_TO_MILLISECONDS;
            if (TimeUnit.NANOSECONDS.toMillis(fileSystemReport.readNanos()) > maxFileReadMillis) {
                reportHeader(sb, OSFileSystemCheck.Report.name(), false);
                addLine(
                        sb,
                        String.format(
                                "OS file read time was too slow. Maximum millis allowed: %d, Took: %s",
                                maxFileReadMillis, readMillis));
                return false;
            } else {
                reportHeader(sb, OSFileSystemCheck.Report.name(), true);
                addLine(sb, fileSystemReport.toString());
                return true;
            }
        } else {
            reportHeader(sb, OSFileSystemCheck.Report.name(), false);
            if (fileSystemReport.exception() == null) {
                addLine(sb, String.format("OS file check failed. Code: %s", fileSystemReport.code()));
            } else {
                addLine(
                        sb,
                        String.format(
                                "OS file check failed with exception. Code: %s%n%s",
                                fileSystemReport.code(), fileSystemReport.exception()));
            }
            return false;
        }
    }
}
