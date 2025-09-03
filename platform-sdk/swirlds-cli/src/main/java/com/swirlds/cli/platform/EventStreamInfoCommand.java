// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.platform;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.platform.util.BootstrapUtils.setupConstructableRegistry;

import com.swirlds.cli.commands.EventStreamCommand;
import com.swirlds.cli.utility.AbstractCommand;
import com.swirlds.cli.utility.SubcommandOf;
import com.swirlds.cli.platform.event.report.EventStreamMultiNodeReport;
import com.swirlds.cli.platform.event.report.EventStreamReport;
import com.swirlds.cli.platform.event.report.EventStreamScanner;
import com.swirlds.cli.internal.EventStreamLowerBound;
import com.swirlds.cli.internal.EventStreamRoundLowerBound;
import com.swirlds.cli.internal.EventStreamTimestampLowerBound;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine;

@CommandLine.Command(
        name = "info",
        mixinStandardHelpOptions = true,
        description = "Read event stream files and print an informational report.")
@SubcommandOf(EventStreamCommand.class)
public final class EventStreamInfoCommand extends AbstractCommand {
    private static final Logger logger = LogManager.getLogger(EventStreamInfoCommand.class);

    /** a format for timestamps */
    private static final String TIMESTAMP_FORMAT = "yyyy-MM-dd HH:mm:ss";

    /** a formatter for timestamps */
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern(TIMESTAMP_FORMAT);

    /** the directory containing the event stream files */
    private Path eventStreamDirectory;

    /** the timestamp of the lower bound */
    private Instant timestampBound = Instant.MIN;

    /** the round of the lower bound */
    private long roundBound = -1;

    /** the default temporal granularity of the data report */
    private Duration granularity = Duration.ofSeconds(10);

    /**
     * If true, a separate report will be generated for each child directory contained in the specified parent.
     * <p>
     * Each individual report will be written to a file in the respective child directory
     * <p>
     * In addition to individual reports, a summary report will be generated and printed to stdout.
     */
    private boolean multiNodeReport = false;

    @CommandLine.Parameters(description = "The path to a directory tree containing event stream files.")
    private void setEventStreamDirectory(final Path eventStreamDirectory) {
        this.eventStreamDirectory = pathMustExist(eventStreamDirectory.toAbsolutePath());
    }

    @CommandLine.Option(
            names = {"-f", "--first-round"},
            description = "The first round to be considered in the event stream.")
    private void setFirstRound(final long firstRound) {
        roundBound = firstRound;
    }

    @CommandLine.Option(
            names = {"-t", "--timestamp"},
            description = "The minimum timestamp to be considered in the event stream. The format is \""
                    + TIMESTAMP_FORMAT + "\".")
    private void setTimestamp(@NonNull final String timestamp) {
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        try {
            // the format used by log4j2
            timestampBound = formatter.parse(timestamp, Instant::from);
        } catch (final DateTimeParseException e) {
            // the format used by Instant.toString()
            timestampBound = Instant.parse(timestamp);
        }
    }

    @CommandLine.Option(
            names = {"-g", "--granularity"},
            description = "The temporal granularity of the data report, in seconds.")
    private void setGranularityInSeconds(final long granularityInSeconds) {
        if (granularityInSeconds < 1) {
            throw buildParameterException("Granularity must be strictly greater than 1");
        }
        this.granularity = Duration.ofSeconds(granularityInSeconds);
    }

    @CommandLine.Option(
            names = {"-m", "--multi-node-report"},
            description =
                    "Generate a separate report for each direct child of the specified directory, as well as a summary report.")
    private void requestMultiNodeReport(final boolean multiNodeReport) {
        this.multiNodeReport = multiNodeReport;
    }

    private EventStreamInfoCommand() {}

    /**
     * Generate an {@link EventStreamReport} for the event stream files contained in the specified directory
     *
     * @param directory the directory containing the event stream files
     * @param bound     the lower bound to use when generating the reports
     * @return the report, or null if the directory does not contain any event stream files
     */
    @Nullable
    private EventStreamReport generateReport(
            @NonNull final Path directory, @NonNull final EventStreamLowerBound bound) {

        Objects.requireNonNull(directory);
        Objects.requireNonNull(bound);

        try {
            return new EventStreamScanner(directory, bound, granularity, true).createReport();
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to generate event stream report", e);
        } catch (final IllegalStateException e) {
            // the directory does not contain any event stream files. return null and let the caller sort it out
            return null;
        }
    }

    /**
     * Write an {@link EventStreamReport} to a file in the specified directory
     *
     * @param nodeDirectory the directory to write the report to
     * @param nodeReport    the report to write to file
     */
    private void writeNodeReportToFile(@NonNull final Path nodeDirectory, @NonNull final EventStreamReport nodeReport) {
        final Path reportFile = nodeDirectory.resolve("event-stream-report.txt");

        try {
            Files.writeString(reportFile, nodeReport.toString());
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to write report to file", e);
        }
    }

    /**
     * Generate an {@link EventStreamReport} for each child directory contained in {@link #eventStreamDirectory}, as
     * well as a summary of these individual reports
     * <p>
     * The individual reports will be written to files in the respective child directories, and the summary report will
     * be printed to stdout
     *
     * @param bound the lower bound to use when generating the reports
     * @throws IOException if the directory stream cannot be opened
     */
    @SuppressWarnings("java:S106")
    private void generateMultiNodeReport(@NonNull final EventStreamLowerBound bound) throws IOException {
        Objects.requireNonNull(bound);

        final EventStreamMultiNodeReport multiReport = new EventStreamMultiNodeReport();

        try (final DirectoryStream<Path> stream = Files.newDirectoryStream(eventStreamDirectory)) {
            stream.forEach(streamElement -> {
                // child elements that aren't directories are ignored
                if (!Files.isDirectory(streamElement)) {
                    return;
                }

                final Path directory = streamElement.normalize();

                final EventStreamReport individualReport = generateReport(directory, bound);

                if (individualReport == null) {
                    logger.error(EXCEPTION.getMarker(), "No event stream files found in `{}`", directory);
                    return;
                }

                multiReport.addIndividualReport(directory, individualReport);
                writeNodeReportToFile(directory, individualReport);
            });
        }
        try {
            System.out.println(multiReport);
        } catch (final NoSuchElementException e) {
            // the multi report is empty
            logger.error(
                    EXCEPTION.getMarker(),
                    "No event stream files found in any child directory of `{}`",
                    eventStreamDirectory);
        }
    }

    @Override
    @SuppressWarnings("java:S106")
    public Integer call() throws Exception {
        setupConstructableRegistry();
        if (roundBound > 0 && !Instant.MIN.equals(timestampBound)) {
            throw buildParameterException("Cannot set both round and timestamp");
        }

        final EventStreamLowerBound bound;
        if (roundBound > 0) {
            bound = new EventStreamRoundLowerBound(roundBound);
        } else if (!Instant.MIN.equals(timestampBound)) {
            bound = new EventStreamTimestampLowerBound(timestampBound);
        } else {
            bound = EventStreamLowerBound.UNBOUNDED;
        }

        if (multiNodeReport) {
            generateMultiNodeReport(bound);
        } else {
            final EventStreamReport report = generateReport(eventStreamDirectory, bound);

            if (report == null) {
                logger.error(EXCEPTION.getMarker(), "No event stream files found in `{}`", eventStreamDirectory);
            } else {
                // an individual report was requested. Simply print the report to stdout.
                System.out.println(report);
            }
        }

        return 0;
    }
}
