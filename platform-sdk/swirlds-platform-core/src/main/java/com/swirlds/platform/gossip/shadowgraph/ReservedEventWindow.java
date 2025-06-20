// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.shadowgraph;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.hiero.consensus.model.hashgraph.EventWindow;

/**
 * An event window that has been reserved in the shadowgraph. While this reservation is held, the shadowgraph will not
 * unlink any events that are non-expired with respect to the reserved window.
 */
public class ReservedEventWindow implements AutoCloseable {
    private final EventWindow eventWindow;
    private final ShadowgraphReservation shadowgraphReservation;

    /**
     * Constructor.
     *
     * @param eventWindow            the event window
     * @param shadowgraphReservation the shadowgraph reservation
     */
    public ReservedEventWindow(
            @NonNull final EventWindow eventWindow, @NonNull final ShadowgraphReservation shadowgraphReservation) {
        this.eventWindow = Objects.requireNonNull(eventWindow);
        this.shadowgraphReservation = Objects.requireNonNull(shadowgraphReservation);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        shadowgraphReservation.close();
    }

    /**
     * Get the reserved event window. No events will be unlinked that are non-expired with respect to this window, as
     * long as this reservation is held.
     *
     * @return the reserved event window
     */
    @NonNull
    public EventWindow getEventWindow() {
        return eventWindow;
    }

    /**
     * Get the number of reservations in the underlying shadowgraph reservation. Exposed for unit tests.
     *
     * @return the number of reservations
     */
    public long getReservationCount() {
        return shadowgraphReservation.getReservationCount();
    }
}
