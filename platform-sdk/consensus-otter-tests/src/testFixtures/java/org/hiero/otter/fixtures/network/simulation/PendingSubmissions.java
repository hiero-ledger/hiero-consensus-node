// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * The events submitted by each node since the previous tick, held until they can be scheduled for delivery.
 *
 * <p>Nodes submit while they are running concurrently, so the order submissions arrive in is decided by the thread
 * scheduler. Each node appends only to its own list, indexed by rank, and no node observes the submissions of another.
 * The lists are drained on a single thread, in an order drawn by {@link #shuffledOrder(Random)}.
 *
 * <p>Submitting is safe from several nodes in parallel, as long as each submits only its own events; everything else
 * is only safe from a single thread while no node is submitting.
 */
final class PendingSubmissions {

    /**
     * The events submitted by each node, indexed by rank, in the order the node submitted them.
     */
    private final List<List<PlatformEvent>> submissions;

    /**
     * The order the submissions are drained in on the current tick. It starts in rank order, and every tick shuffles the
     * order of the previous tick in place, so that producing it does not allocate.
     */
    private final List<Integer> order;

    /**
     * Constructor.
     *
     * @param nodeCount the number of nodes in the network
     */
    PendingSubmissions(final int nodeCount) {
        submissions = new ArrayList<>(nodeCount);
        for (int rank = 0; rank < nodeCount; rank++) {
            submissions.add(new ArrayList<>());
        }

        order = new ArrayList<>(nodeCount);
        for (int rank = 0; rank < nodeCount; rank++) {
            order.add(rank);
        }
    }

    /**
     * Holds an event submitted by a node.
     *
     * @param submitter the rank of the node
     * @param event     the event
     */
    void submit(final int submitter, @NonNull final PlatformEvent event) {
        submissions.get(submitter).add(event);
    }

    /**
     * Gets the order the submissions are to be drained in on the current tick.
     *
     * <p>Which node goes first is drawn rather than fixed, so that a scenario which always targets the same node -
     * isolating it, or narrowing its bandwidth - is not always paired with the same position in the schedule. A fixed
     * order would let one node's events systematically precede another's for a whole run.
     *
     * <p>Each order is a shuffle of the previous one, which is as uniformly random as a shuffle of the ranks in rank
     * order. It still leaves the result reproducible, because it depends only on the random number generator, which
     * every previous order was drawn from too, and on the rank order the first one starts from, neither of which is
     * affected by how the nodes interleaved their submissions.
     *
     * @param random the random number generator to draw the order from
     * @return the ranks of the nodes in the order to drain them in, which is only valid until the next call
     */
    @NonNull
    List<Integer> shuffledOrder(@NonNull final Random random) {
        Collections.shuffle(order, random);
        return order;
    }

    /**
     * Gets the events submitted by a node, which the caller clears once it has dealt with them.
     *
     * @param submitter the rank of the node
     * @return the events, in the order the node submitted them
     */
    @NonNull
    List<PlatformEvent> eventsOf(final int submitter) {
        return submissions.get(submitter);
    }
}
