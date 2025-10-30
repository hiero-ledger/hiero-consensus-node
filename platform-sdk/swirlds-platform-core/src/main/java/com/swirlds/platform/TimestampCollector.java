// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TimestampCollector {

    public enum Position {
        GOSSIP_ENTERED,
        EVENT_HASHED,
        EVENT_VALIDATED,
        EVENT_DEDUPLICATED,
        SIGNATURE_VALIDATED,
        ORPHAN_BUFFER_ENTERED,
        ORPHAN_BUFFER_RELEASED,
        EVENT_PERSISTED,
        EVENT_ADDED_TO_HASHGRAPH,
        FUTURE_BUFFER_ENTERED,
        FUTURE_BUFFER_RELEASED,
        PARENTS_LINKED,
        CONSENSUS_ADDED,
        ROUND_CALCULATED,
        WITNESS_DETECTED,
        FAME_DECIDED,
        CONSENSUS_REACHED
    }

    public static final int GAP = 100;
    private static final int MAX_ELEMENTS = 1000;
    private static final Duration WARMUP = Duration.ofMinutes(1L);
    private static final long THRESHOLD_NANOS = System.nanoTime() + WARMUP.toNanos();

    private static final AtomicLong COUNTER = new AtomicLong();
    private static final long[][] timestamps = new long[MAX_ELEMENTS][Position.values().length];

    public static int register() {
        final long now = System.nanoTime();
        if (now < THRESHOLD_NANOS) {
            return 0;
        }
        final long count = TimestampCollector.COUNTER.incrementAndGet();
        if (count % TimestampCollector.GAP == 0) {
            final int index = (int) (count / TimestampCollector.GAP);
            TimestampCollector.timestamp(Position.GOSSIP_ENTERED, index);
            return index;
        }
        return 0;
    }

    public static void timestamp(@NonNull final Position position, final int index) {
        if (index >= MAX_ELEMENTS) {
            return;
        }
        final long now = System.nanoTime();
        timestamps[index][position.ordinal()] = now;
    }

    public static void store() {
        try (final BufferedWriter writer = new BufferedWriter(new FileWriter("timestamps.csv"))) {
            final String heading = Stream.of(Position.values()).skip(1L).map(Position::toString).collect(Collectors.joining(","));
            writer.write(heading);
            writer.newLine();

            // If all rows have 0 in the last column, none reached consensus, and we want to dump all rows
            final boolean dumpAll = Stream.of(timestamps).allMatch(row -> row[row.length - 1] == 0);

            for (final long[] row : timestamps) {

                // Skip rows where the first timestamp is 0 (uninitialized)
                if (row[0] == 0) {
                    continue;
                }

                if (!dumpAll && row[row.length - 1] == 0) {
                    continue; // event did not reach consensus
                }

                // Calculate differences from the first column
                final StringBuilder line = new StringBuilder();
                for (int j = 1; j < row.length; j++) {
                    if (j > 1) {
                        line.append(",");
                    }
                    final long difference = row[j] - row[0];
                    if (difference > 0) {
                        line.append(difference);
                    }
                }

                writer.write(line.toString());
                writer.newLine();
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to write timestamps to file", e);
        }
    }
}
