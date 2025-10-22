// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform;

import com.swirlds.logging.legacy.LogMarker;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class TimestampCollector {

    private static final Logger log = LogManager.getLogger();

    public enum Position {
        GOSSIP_ENTERED,
        EVENT_HASHED,
        EVENT_VALIDATED,
        SIGNATURE_VALIDATED,
        ORPHAN_BUFFER_ENTERED,
        ORPHAN_BUFFER_RELEASED,
        EVENT_PERSISTED,
        FUTURE_BUFFER_ENTERED,
        FUTURE_BUFFER_RELEASED,
        PARENTS_LINKED,
        CONSENSUS_ADDED,
        ROUND_CALCULATED,
        WITNESS_DETECTED,
        FAME_DECIDED,
        CONSENSUS_REACHED
    }

    public static final int GAP = 10;
    public static final AtomicLong COUNTER = new AtomicLong();

    private static final int MAX_ELEMENTS = 1000;

    private static final long[][] timestamps = new long[MAX_ELEMENTS][Position.values().length];

    public static void timestamp(@NonNull final Position position, final int index) {
        if (index >= MAX_ELEMENTS) {
            return;
        }
        timestamps[index][position.ordinal()] = System.nanoTime();
    }

    public static void store() {
        log.info(LogMarker.DEMO_INFO.getMarker(), "TimestampCollector storing");
        try (final BufferedWriter writer = new BufferedWriter(new FileWriter("timestamps.csv"))) {
            final String heading = Stream.of(Position.values()).map(Position::toString).collect(Collectors.joining(","));
            writer.write(heading);
            writer.newLine();
            for (int i = 0, n = timestamps.length; i < n; i++) {

                final long[] row = timestamps[i];

                log.info(LogMarker.DEMO_INFO.getMarker(), "TimestampCollector store: {} at index {}", i, row);

                // Skip rows where the first timestamp is 0 (uninitialized)
                if (row[0] == 0) {
                    continue;
                }

                if (row[row.length - 1] == 0) {
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
            throw new RuntimeException("Failed to write timestamps to file", e);
        }
    }
}
