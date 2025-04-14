// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.report;

import static com.swirlds.common.formatting.StringFormattingUtils.commaSeparatedNumber;
import static com.swirlds.common.formatting.TextEffect.BRIGHT_RED;
import static com.swirlds.common.formatting.TextEffect.BRIGHT_YELLOW;
import static org.hiero.base.utility.CompareTo.isGreaterThan;

import com.swirlds.common.formatting.UnitFormatter;
import com.swirlds.common.io.IOIterator;
import com.swirlds.common.units.TimeUnit;
import com.swirlds.platform.recovery.internal.EventStreamLowerBound;
import com.swirlds.platform.recovery.internal.EventStreamMultiFileIterator;
import com.swirlds.platform.recovery.internal.MultiFileRunningHashIterator;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.hiero.consensus.model.event.CesEvent;

/**
 * Scans an event stream and generates a report.
 */
public class EventStreamScanner {

    private static final int PROGRESS_INTERVAL = 10_000;

    private long eventCount = 0;
    private long applicationTransactionCount = 0;

    // These variables store data for the higher granularity reports
    private final List<EventStreamInfo> granularInfo = new ArrayList<>();
    private CesEvent granularFirstEvent;
    private long granularStartingFileCount = 0;
    private long previousFileCount = 0;
    private long previousDamagedFileCount = 0;
    private long previousByteCount = 0;
    private long granularStartingDamagedFileCount = 0;
    private long granularEventCount = 0;
    private long granularApplicationTransactionCount = 0;

    private final Duration reportPeriod;
    private final boolean enableProgressReport;

    private final EventStreamMultiFileIterator fileIterator;
    private final IOIterator<CesEvent> eventIterator;

    public EventStreamScanner(
            @NonNull final Path eventStreamDirectory,
            @NonNull final EventStreamLowerBound lowerBound,
            @NonNull final Duration reportPeriod,
            final boolean enableProgressReport)
            throws IOException {
        Objects.requireNonNull(eventStreamDirectory, "the event stream directory must not be null");
        Objects.requireNonNull(lowerBound, "the lower bound must not be null");
        this.reportPeriod = Objects.requireNonNull(reportPeriod, "the report period must not be null");
        this.enableProgressReport = enableProgressReport;

        fileIterator = new EventStreamMultiFileIterator(eventStreamDirectory, lowerBound);
        eventIterator = new MultiFileRunningHashIterator(fileIterator);
    }

    /**
     * Time is split into "chunks". For each chunk of time we generate a mini-report. When this method is called
     * we gather all the data from a single chunk of time.
     */
    private void reportGranularData(final CesEvent lastEventInPeriod) {
        final long granularRoundCount = lastEventInPeriod.getRoundReceived() - granularFirstEvent.getRoundReceived();

        granularInfo.add(new EventStreamInfo(
                granularFirstEvent.getPlatformEvent().getConsensusTimestamp(),
                lastEventInPeriod.getPlatformEvent().getConsensusTimestamp(),
                granularRoundCount,
                granularEventCount,
                granularApplicationTransactionCount,
                previousFileCount - granularStartingFileCount,
                fileIterator.getBytesRead() - previousByteCount,
                granularFirstEvent,
                lastEventInPeriod,
                previousDamagedFileCount - granularStartingDamagedFileCount));
    }

    /**
     * This should be called between each "chunk" of time for which we collect granular data.
     */
    private void resetGranularData(final CesEvent mostRecentEvent) {
        granularFirstEvent = mostRecentEvent;
        granularEventCount = 0;
        granularApplicationTransactionCount = 0;
        granularStartingFileCount = fileIterator.getFileCount();
        granularStartingDamagedFileCount = fileIterator.getDamagedFileCount();
        previousByteCount = fileIterator.getBytesRead();
    }

    /**
     * Collect data from an event.
     */
    private void collectEventData(final CesEvent mostRecentEvent) {
        eventCount++;
        granularEventCount++;
        mostRecentEvent.getPlatformEvent().transactionIterator().forEachRemaining(transaction -> {
            applicationTransactionCount++;
            granularApplicationTransactionCount++;
        });
    }

    /**
     * Write information to the console that let's a human know of the progress of the scan.
     */
    private void writeConsoleSummary(final CesEvent firstEvent, final CesEvent mostRecentEvent) {

        if (enableProgressReport && eventCount % PROGRESS_INTERVAL == 0) {
            // This is intended to be used in a terminal with a human in the loop, intentionally not logged.
            final Duration consensusTimeProcessed = Duration.between(
                    firstEvent.getPlatformEvent().getConsensusTimestamp(),
                    mostRecentEvent.getPlatformEvent().getConsensusTimestamp());

            final UnitFormatter formatter = TimeUnit.UNIT_MILLISECONDS
                    .buildFormatter()
                    .setQuantity(consensusTimeProcessed.toMillis())
                    .setDecimalPlaces(2)
                    .setAbbreviate(false);

            System.out.print(" > " + BRIGHT_RED.apply(commaSeparatedNumber(eventCount))
                    + " events have been parsed from a consensus timespan of "
                    + BRIGHT_YELLOW.apply(formatter.render())
                    + "             \r");
            System.out.flush();
        }
    }

    public EventStreamReport createReport() throws IOException {

        if (!eventIterator.hasNext()) {
            throw new IllegalStateException("No events found in the event stream");
        }

        final CesEvent firstEvent = eventIterator.peek();
        granularFirstEvent = firstEvent;

        CesEvent mostRecentEvent = null;
        CesEvent previousEvent;
        while (eventIterator.hasNext()) {
            previousEvent = mostRecentEvent;
            mostRecentEvent = eventIterator.next();

            final Duration elapsedGranularTime = Duration.between(
                    granularFirstEvent.getPlatformEvent().getConsensusTimestamp(),
                    mostRecentEvent.getPlatformEvent().getConsensusTimestamp());
            if (previousEvent != null && isGreaterThan(elapsedGranularTime, reportPeriod)) {
                // The previous granular period has ended. Start a new period.
                reportGranularData(previousEvent);
                resetGranularData(mostRecentEvent);
            }

            collectEventData(mostRecentEvent);

            previousFileCount = fileIterator.getFileCount();
            previousDamagedFileCount = fileIterator.getDamagedFileCount();

            if (!eventIterator.hasNext() && granularEventCount > 0) {
                reportGranularData(mostRecentEvent);
            }

            writeConsoleSummary(firstEvent, mostRecentEvent);
        }

        final long rounds = mostRecentEvent.getRoundReceived() - firstEvent.getRoundReceived();

        return new EventStreamReport(
                granularInfo,
                new EventStreamInfo(
                        firstEvent.getPlatformEvent().getConsensusTimestamp(),
                        mostRecentEvent.getPlatformEvent().getConsensusTimestamp(),
                        rounds,
                        eventCount,
                        applicationTransactionCount,
                        fileIterator.getFileCount(),
                        fileIterator.getBytesRead(),
                        firstEvent,
                        mostRecentEvent,
                        fileIterator.getDamagedFileCount()));
    }
}
