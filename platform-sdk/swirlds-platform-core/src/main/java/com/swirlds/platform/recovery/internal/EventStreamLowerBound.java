// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.recovery.internal;

import org.hiero.consensus.model.event.CesEvent;

/**
 * A lower bound on events in an event stream based on the consensus data in the events. The bound is inclusive of exact
 * matches. The compareTo(event) returns the event's ordered relationship to the bound: a value greater than 0 if the
 * event is greater than the bound,  0 if the event is equal to the bound, and a value less than 0 if the event is less
 * than the bound.
 */
public interface EventStreamLowerBound {

    /**
     * An unbounded lower bound, all events are greater than it.
     */
    EventStreamLowerBound UNBOUNDED = consensusData -> 1;

    /**
     * Compares an event to a lower bound based on the comparison of its consensus data.
     *
     * @param event the event to compare
     * @return a value greater than, equal to, or less than 0, if the event is greater than, equal to, or less than the
     * bound.
     */
    int compareTo(CesEvent event);
}
