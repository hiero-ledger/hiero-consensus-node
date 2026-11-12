// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation.duplicates;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * The events that have been submitted to a simulated network and are not expired, linked to their parents, as the
 * duplicate estimate sees them.
 *
 * <p>Events are identified by their descriptor; two events that differ only in their signature are taken to be one.
 * They are kept in the order they were first submitted, which is topological, because a node only submits an event once
 * it has its parents.
 *
 * <p>This class is not safe to use from more than one thread.
 */
final class GossipGraph {

    /**
     * The number of nodes in the network.
     */
    private final int nodeCount;

    /**
     * The events, by descriptor.
     */
    private final Map<EventDescriptorWrapper, GraphEvent> byDescriptor = new HashMap<>();

    /**
     * The events, in the order they were first submitted.
     */
    private final ArrayDeque<GraphEvent> inOrder = new ArrayDeque<>();

    /**
     * Constructor.
     *
     * @param nodeCount the number of nodes in the network
     */
    GossipGraph(final int nodeCount) {
        this.nodeCount = nodeCount;
    }

    /**
     * Gets the event of a submitted instance, adding it, linked to those of its parents that are in the graph, if it is
     * submitted for the first time.
     *
     * @param event   the submitted instance
     * @param creator the rank of the node that created the event, or {@code -1}
     * @return the event in the graph
     */
    @NonNull
    GraphEvent add(@NonNull final PlatformEvent event, final int creator) {
        final GraphEvent existing = byDescriptor.get(event.getDescriptor());
        if (existing != null) {
            return existing;
        }
        final GraphEvent added = new GraphEvent(event, creator, nodeCount);
        for (final EventDescriptorWrapper parent : event.getAllParents()) {
            final GraphEvent parentEvent = byDescriptor.get(parent);
            if (parentEvent != null) {
                added.linkParent(parentEvent);
            }
        }
        byDescriptor.put(event.getDescriptor(), added);
        inOrder.addLast(added);
        return added;
    }

    /**
     * @return the events, in the order they were first submitted, which the caller must not modify
     */
    @NonNull
    Iterable<GraphEvent> inOrder() {
        return inOrder;
    }

    /**
     * Forgets the oldest events that are expired for every node, and unlinks them from the events that remain. Events
     * are only forgotten from the start of the order they were submitted in, so an expired event can linger behind one
     * that is not expired yet.
     *
     * @param expiredThreshold the lowest expired threshold of any node
     */
    void forgetExpired(final long expiredThreshold) {
        GraphEvent oldest;
        while ((oldest = inOrder.peekFirst()) != null && oldest.birthRound() < expiredThreshold) {
            inOrder.pollFirst();
            byDescriptor.remove(oldest.event().getDescriptor());
            oldest.unlink();
        }
    }
}
