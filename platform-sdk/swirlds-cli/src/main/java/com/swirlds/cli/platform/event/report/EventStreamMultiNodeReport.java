// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.platform.event.report;

import static com.swirlds.common.formatting.TextEffect.BRIGHT_RED;
import static com.swirlds.common.formatting.TextEffect.BRIGHT_YELLOW;

import com.swirlds.common.formatting.TextTable;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

/**
 * A report summarizing a collection of event stream files from multiple nodes
 */
public class EventStreamMultiNodeReport {
    /**
     * A map from a directory to an {@link EventStreamInfo} which summarizes that event stream files in that directory
     */
    private final Map<String, EventStreamInfo> individualReports = new HashMap<>();

    /**
     * Add an individual node report to this multi-node report
     *
     * @param directory        the directory containing the event stream files of the individual report
     * @param individualReport the individual report
     */
    public void addIndividualReport(@NonNull final Path directory, @NonNull final EventStreamReport individualReport) {
        Objects.requireNonNull(directory);
        Objects.requireNonNull(individualReport);

        individualReports.put(directory.toString(), individualReport.summary());
    }

    @Override
    @NonNull
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("\n");

        final Entry<String, EventStreamInfo> entryWithLatestEvent = individualReports.entrySet().stream()
                .max(Map.Entry.comparingByValue(((o1, o2) -> {
                    final Instant o1Timestamp =
                            o1.lastEvent().getPlatformEvent().getConsensusTimestamp();
                    final Instant o2Timestamp =
                            o2.lastEvent().getPlatformEvent().getConsensusTimestamp();

                    return o1Timestamp.compareTo(o2Timestamp);
                })))
                .orElseThrow();

        final Entry<String, EventStreamInfo> entryWithMostEvents = individualReports.entrySet().stream()
                .max(Map.Entry.comparingByValue((Comparator.comparingLong(EventStreamInfo::eventCount))))
                .orElseThrow();

        new TextTable()
                .setTitle("Multi-node Event Stream Summary")
                .addRow(BRIGHT_RED.apply("Directory with latest event"), entryWithLatestEvent.getKey())
                .addRow(
                        BRIGHT_YELLOW.apply("Latest event consensus timestamp"),
                        entryWithLatestEvent
                                .getValue()
                                .lastEvent()
                                .getPlatformEvent()
                                .getConsensusTimestamp())
                .addRow(BRIGHT_RED.apply("Directory with most events"), entryWithMostEvents.getKey())
                .addRow(
                        BRIGHT_YELLOW.apply("Number of events"),
                        entryWithMostEvents.getValue().eventCount())
                .render(sb);
        sb.append("\n\n");

        return sb.toString();
    }
}
