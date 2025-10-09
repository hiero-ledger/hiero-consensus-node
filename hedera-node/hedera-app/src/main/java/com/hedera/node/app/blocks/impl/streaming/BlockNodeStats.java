// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

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
     * Map for tracking the timestamps when blocks are sent to the block node.
     * The key is the block number and the value is the timestamp when the block was sent.
     */
    private final Map<Long, Instant> blockProofSendTimestamps = new ConcurrentHashMap<>();

    /**
     * Counter for tracking consecutive high-latency events.
     */
    private final AtomicInteger consecutiveHighLatencyEvents = new AtomicInteger(0);

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

    /**
     * Records the time when a block proof was sent to a block node.
     *
     * @param blockNumber the block number of the sent proof
     * @param timestamp the time the block was sent
     */
    public void recordBlockProofSent(final long blockNumber, @NonNull final Instant timestamp) {
        requireNonNull(timestamp, "timestamp must not be null");
        blockProofSendTimestamps.put(blockNumber, timestamp);
    }

    /**
     * Records an acknowledgement for a block and evaluates whether the latency is considered high.
     * If the latency exceeds the specified threshold, increments the consecutive high-latency counter.
     * If the latency is below or equal to the threshold, resets the counter.
     *
     * @param blockNumber the acknowledged block number
     * @param acknowledgedTime the time the acknowledgement was received
     * @param highLatencyThreshold threshold above which latency is considered high
     * @param eventsBeforeSwitching the number of consecutive high-latency events that triggers a switch
     * @return a result describing the evaluation: latency (ms), consecutive count, and whether the threshold was exceeded enough to switch
     */
    public HighLatencyResult recordAcknowledgementAndEvaluate(
            final long blockNumber,
            @NonNull final Instant acknowledgedTime,
            final Duration highLatencyThreshold,
            final int eventsBeforeSwitching) {
        requireNonNull(acknowledgedTime, "acknowledgedTime must not be null");

        final Instant sendTime = blockProofSendTimestamps.get(blockNumber);

        // Prune the map of all entries with block numbers less than or equal to the acknowledged block number.
        blockProofSendTimestamps.keySet().removeIf(key -> key <= blockNumber);

        if (sendTime == null) {
            // No sent timestamp found; treat as no-op for high-latency accounting
            return new HighLatencyResult(0L, consecutiveHighLatencyEvents.get(), false, false);
        }

        final long latencyMs = Duration.between(sendTime, acknowledgedTime).toMillis();
        final boolean isHighLatency = latencyMs > highLatencyThreshold.toMillis();
        int consecutiveCount;
        boolean shouldSwitch = false;

        synchronized (consecutiveHighLatencyEvents) {
            if (isHighLatency) {
                consecutiveCount = consecutiveHighLatencyEvents.incrementAndGet();
                if (consecutiveCount >= eventsBeforeSwitching) {
                    shouldSwitch = true;
                    // Reset after indicating switch to prevent repeated triggers without new evidence
                    consecutiveHighLatencyEvents.set(0);
                }
            } else {
                consecutiveHighLatencyEvents.set(0);
                consecutiveCount = 0;
            }
        }

        return new HighLatencyResult(latencyMs, consecutiveCount, isHighLatency, shouldSwitch);
    }

    /**
     * A simple immutable result describing the outcome of a latency evaluation.
     * @param latencyMs the latency in milliseconds
     * @param consecutiveHighLatencyEvents the number of consecutive high-latency events
     * @param isHighLatency whether the latency is considered high enough to trigger a switch
     * @param shouldSwitch whether the latency should trigger a switch
     */
    public record HighLatencyResult(
            long latencyMs, int consecutiveHighLatencyEvents, boolean isHighLatency, boolean shouldSwitch) {}
}
