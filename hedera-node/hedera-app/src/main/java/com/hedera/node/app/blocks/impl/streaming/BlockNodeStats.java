// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Tracks information for a block node across multiple connection instances.
 * This data persists beyond individual BlockNodeConnection lifecycles to properly
 * implement rate limiting and health monitoring.
 */
public class BlockNodeStats {
    /**
     * Queue for tracking EndOfStream response timestamps for rate limiting.
     */
    private final Queue<Instant> endOfStreamTimestamps = new ConcurrentLinkedQueue<>();

    /**
     * Returns the current count of EndOfStream events tracked.
     *
     * @return the number of EndOfStream events currently tracked
     */
    public int getEndOfStreamCount() {
        return endOfStreamTimestamps.size();
    }

    /**
     * Adds a new EndOfStream event timestamp, prunes any old timestamps that are outside the time window,
     * and then checks if the number of EndOfStream events exceeds the configured maximum.
     *
     * @param timestamp the timestamp of the last EndOfStream response received
     * @param maxAllowed the maximum number of EndOfStream responses allowed in the time window
     * @param timeFrame the time window for counting EndOfStream responses
     * @return true if the number of EndOfStream responses exceeds the maximum, otherwise false
     */
    public boolean addEndOfStreamAndCheckLimit(
            @NonNull Instant timestamp, int maxAllowed, @NonNull Duration timeFrame) {
        requireNonNull(timestamp, "timestamp must not be null");
        requireNonNull(timeFrame, "timeFrame must not be null");

        // Add the current timestamp to the queue
        endOfStreamTimestamps.add(timestamp);

        final Instant now = Instant.now();
        final Instant cutoff = now.minus(timeFrame);

        // Remove expired timestamps
        final Iterator<Instant> it = endOfStreamTimestamps.iterator();
        while (it.hasNext()) {
            final Instant endOfStreamTimestamp = it.next();
            if (endOfStreamTimestamp.isBefore(cutoff)) {
                it.remove();
            } else {
                break;
            }
        }
        return endOfStreamTimestamps.size() > maxAllowed;
    }
}
