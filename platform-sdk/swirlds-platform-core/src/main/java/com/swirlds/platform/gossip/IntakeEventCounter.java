// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.model.node.NodeId;

/**
 * Keeps track of how many events have been received from each peer, but haven't yet made it through the intake
 * pipeline.
 */
public interface IntakeEventCounter {
    /**
     * Checks whether there are any events from a given sender that have entered the intake pipeline, but aren't yet
     * through it.
     *
     * @param peer the peer to check for unprocessed events
     * @return true if there are unprocessed events, false otherwise
     */
    boolean hasUnprocessedEvents(@NonNull final NodeId peer);

    /**
     * Indicates that an event from a given peer has entered the intake pipeline
     *
     * @param peer the peer that sent the event
     */
    void eventEnteredIntakePipeline(@NonNull final NodeId peer);

    /**
     * Indicates that an event from a given peer has exited the intake pipeline
     *
     * @param peer the peer that sent the event
     */
    void eventExitedIntakePipeline(@Nullable final NodeId peer);

    /**
     * Reset event counts
     */
    void reset();
}
