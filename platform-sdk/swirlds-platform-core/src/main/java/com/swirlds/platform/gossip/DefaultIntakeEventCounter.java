// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip;

import com.hedera.hapi.node.state.roster.Roster;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;
import org.hiero.consensus.model.node.NodeId;

/**
 * Default implementation of {@link IntakeEventCounter}.
 * <p>
 * Tracks the number of events from each peer that have been added to the intake pipeline, but haven't yet made it
 * through.
 */
public class DefaultIntakeEventCounter implements IntakeEventCounter {
    /**
     * Lambda to update the intake counter when an event exits the intake pipeline. The lambda decrements the counter,
     * but prevents it from going below 0.
     */
    private static final IntUnaryOperator EXIT_INTAKE = count -> {
        if (count > 0) {
            return count - 1;
        } else {
            throw new IllegalStateException(
                    "Event processed from peer, but no events from that peer were in the intake pipeline. This shouldn't be possible.");
        }
    };

    /**
     * A map from node id to an atomic integer, which represents the number of events from that peer that have been
     * added to the intake pipeline, but haven't yet made it through.
     */
    private final Map<NodeId, AtomicInteger> unprocessedEventCounts;

    /**
     * Constructor
     *
     * @param roster the roster
     */
    public DefaultIntakeEventCounter(@NonNull final Roster roster) {
        this.unprocessedEventCounts = new HashMap<>();
        roster.rosterEntries()
                .forEach(entry -> unprocessedEventCounts.put(NodeId.of(entry.nodeId()), new AtomicInteger(0)));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasUnprocessedEvents(@NonNull final NodeId peer) {
        Objects.requireNonNull(peer);

        return unprocessedEventCounts.get(peer).get() > 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void eventEnteredIntakePipeline(@NonNull final NodeId peer) {
        Objects.requireNonNull(peer);

        unprocessedEventCounts.get(peer).incrementAndGet();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void eventExitedIntakePipeline(@Nullable final NodeId peer) {
        if (peer == null) {
            // this will happen if the event wasn't received through normal gossip
            return;
        }

        unprocessedEventCounts.get(peer).getAndUpdate(EXIT_INTAKE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reset() {
        unprocessedEventCounts.forEach((nodeId, unprocessedEventCount) -> unprocessedEventCount.set(0));
    }
}
