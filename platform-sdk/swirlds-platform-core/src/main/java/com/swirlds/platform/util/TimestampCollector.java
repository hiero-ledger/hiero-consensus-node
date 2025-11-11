package com.swirlds.platform.util;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;

/**
 * A singleton class for collecting timestamps of various events during the processing of platform events.
 */
public enum TimestampCollector {

    /** The singleton instance of the TimestampCollector. */
    INSTANCE;

    /** The various positions in the event processing pipeline where timestamps can be recorded. */
    public enum Position {
        /** Timestamp when the event enters the system. */
        GOSSIP_ENTERED,

        /** Timestamp after the event was hashed. */
        EVENT_HASHED,

        /** Timestamp after the event was verified. */
        EVENT_VALIDATED,

        /** Timestamp after the event was checked for duplication. */
        EVENT_DEDUPLICATED,

        /** Timestamp after the event's signature was validated. */
        SIGNATURE_VALIDATED,

        /** Timestamp when the event is placed in the orphan buffer. */
        ORPHAN_BUFFER_ENTERED,

        /** Timestamp when the event is released from the orphan buffer. */
        ORPHAN_BUFFER_RELEASED,

        /** Timestamp after the event has been written to the PCES-file. */
        EVENT_PERSISTED,

        /** Timestamp when the event is added to the hashgraph module. */
        EVENT_ADDED_TO_HASHGRAPH,

        /** Timestamp after the event's parents have been linked. */
        PARENTS_LINKED,

        /** Timestamp after the event has been added to the consensus engine. */
        CONSENSUS_ADDED,

        /** Timestamp after the round has been calculated for the event. */
        ROUND_CALCULATED,

        /** Timestamp after witnesses have been determined for the event. */
        WITNESS_DETECTED,

        /** Timestamp after fame has been decided for the event. */
        FAME_DECIDED,

        /** Timestamp when consensus has been reached for the event. */
        CONSENSUS_REACHED
    }

    private static final int GAP = 100;
    private static final int MAX_ELEMENTS = 1000;
    private static final Duration WARMUP = Duration.ofMinutes(1L);
    private static final long WARMUP_NANOS = System.nanoTime() + WARMUP.toNanos();

    private final AtomicLong counter = new AtomicLong();
    private final long[][] timestamps = new long[MAX_ELEMENTS][Position.values().length];
    private final AtomicBoolean done = new AtomicBoolean(false);

    private NodeId selfId;

    /**
     * Initialize the {@code TimestampCollector}
     *
     * @param selfId the {@link NodeId} of the node
     */
    public void init(@NonNull final NodeId selfId) {
        this.selfId = requireNonNull(selfId);
    }

    /**
     * Registers a new event for timestamp collection.
     *
     * @param event the platform event to register
     * @return the index assigned to the event for timestamp collection, or -1 if the event will not be tracked
     */
    public int register(@NonNull final PlatformEvent event) {
        if (System.nanoTime() > WARMUP_NANOS) {
            final long count = counter.incrementAndGet();
            if (count % TimestampCollector.GAP == 0) {
                final int index = (int) (count / TimestampCollector.GAP);
                if (index < MAX_ELEMENTS) {
                    event.setTimestampIndex(index);
                    timestamp(Position.GOSSIP_ENTERED, event);
                    return index;
                } else if (index == MAX_ELEMENTS) {
                    store();
                }
            }
        }
        return -1;
    }

    /**
     * Records a timestamp for the specified position in the event processing pipeline.
     *
     * @param position the position in the event processing pipeline
     * @param event    the platform event for which to record the timestamp
     */
    public void timestamp(@NonNull final Position position, @NonNull final PlatformEvent event) {
        final int index = event.getTimestampIndex();
        if (index >= 0) {
            timestamps[index][position.ordinal()] = System.nanoTime();
        }
    }

    /**
     * Stores the collected timestamps to a CSV file.
     */
    public void store() {
        if (done.compareAndSet(false, true)) {
            try (final BufferedWriter writer = new BufferedWriter(new FileWriter("timestamps" + selfId.id() + ".csv"))) {
                final String heading = Stream.of(Position.values()).skip(1L).map(Position::toString)
                        .collect(Collectors.joining(","));
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
}