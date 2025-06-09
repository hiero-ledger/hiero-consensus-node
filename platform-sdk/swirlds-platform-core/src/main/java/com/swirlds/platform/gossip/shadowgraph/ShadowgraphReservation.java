// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.shadowgraph;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents zero or more reservations for an ancient indicator (i.e. the birth round). It is used to determine when it
 * is safe to expire events in a given ancient indicator. Reservations are made by gossip threads inside
 * {@link Shadowgraph}. Ancient indicators that have at least one reservation may not have any of its events expired.
 */
public final class ShadowgraphReservation implements AutoCloseable {

    /**
     * The threshold that is being reserved. No event with an ancient indicator greater than or equal to this value may
     * be expired.
     */
    private final long reservedThreshold;

    /**
     * The number of reservations on this ancient indicator.
     */
    private final AtomicInteger reservationCount;

    /**
     * Constructor.
     *
     * @param reservedThreshold the ancient indicator that is being reserved, no event with an ancient indicator greater
     *                          than or equal to this value may be expired
     */
    public ShadowgraphReservation(final long reservedThreshold) {
        this.reservedThreshold = reservedThreshold;
        reservationCount = new AtomicInteger(1);
    }

    /**
     * Increments the number of reservations on this ancient indicator.
     */
    public void incrementReservations() {
        reservationCount.incrementAndGet();
    }

    /**
     * Returns the number of current reservations for this ancient indicator.
     *
     * @return number of reservations
     */
    public int getReservationCount() {
        return reservationCount.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        reservationCount.decrementAndGet();
    }

    /**
     * Returns the ancient indicator that this instance tracks reservations for. The returned value is always zero or
     * greater.
     *
     * @return the ancient indicator that is reserved
     */
    public long getReservedThreshold() {
        return reservedThreshold;
    }
}
