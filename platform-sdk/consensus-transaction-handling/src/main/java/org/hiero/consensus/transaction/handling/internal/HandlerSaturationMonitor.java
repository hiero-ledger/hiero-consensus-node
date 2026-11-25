// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.transaction.handling.internal;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.function.LongSupplier;

/**
 * Reports how long the transaction handler's queue has been full. A full queue holds as many rounds as its capacity
 * allows; with hard backpressure, consensus then waits for the handler. The execution layer refuses new client
 * transactions once the queue has been full for longer than its threshold.
 */
public class HandlerSaturationMonitor {

    /** The number of rounds in the handler's queue, including the one being handled. */
    private final LongSupplier queuedRounds;

    /** The capacity of the handler's queue; zero or less if the queue is unbounded and therefore never full. */
    private final long capacity;

    /** When the queue was first seen full in the current stretch of full checks, or null if it was not full. */
    @Nullable
    private Instant fullSince;

    /**
     * Constructor.
     *
     * @param queuedRounds the number of rounds in the handler's queue
     * @param capacity     the capacity of the handler's queue; zero or less if unbounded
     */
    public HandlerSaturationMonitor(@NonNull final LongSupplier queuedRounds, final long capacity) {
        this.queuedRounds = requireNonNull(queuedRounds);
        this.capacity = capacity;
    }

    /**
     * Checks the handler's queue.
     *
     * @param now the time of the check
     * @return how long the queue has been full, or {@link Duration#ZERO} if it is not full
     */
    @NonNull
    public Duration check(@NonNull final Instant now) {
        final boolean full = capacity > 0 && queuedRounds.getAsLong() >= capacity;
        if (!full) {
            fullSince = null;
            return Duration.ZERO;
        }
        if (fullSince == null) {
            fullSince = now;
        }
        return Duration.between(fullSince, now);
    }
}
