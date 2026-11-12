// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation.duplicates;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * An event of the {@link GossipGraph}, with what the duplicate estimate needs to know about it: its parents and
 * children, when each node knows it, and the first copy of it that reaches each node.
 *
 * <p>Nodes are identified by their rank, which is their index in the roster. Times are nanoseconds since the start of
 * the simulation.
 *
 * <p>This class is not safe to use from more than one thread.
 */
final class GraphEvent {

    /**
     * Stands for a time that has not come: a node that does not know the event, or a node no copy has reached.
     */
    static final long NEVER = Long.MAX_VALUE;

    /**
     * The first submitted instance of the event.
     */
    private final PlatformEvent event;

    /**
     * The rank of the node that created the event, or {@code -1} if that node is not part of the network.
     */
    private final int creator;

    /**
     * The parents of the event that are in the graph.
     */
    private final List<GraphEvent> parents = new ArrayList<>(4);

    /**
     * The events in the graph that have this event as a parent.
     */
    private final List<GraphEvent> children = new ArrayList<>(4);

    /**
     * The time each node knows the event from, indexed by rank, or {@link #NEVER}.
     */
    private final long[] knownFrom;

    /**
     * The arrival time of the first copy of the event at each node, indexed by rank, or {@link #NEVER}. {@code null}
     * until the first copy is recorded.
     */
    @Nullable
    private long[] firstCopyArrivals;

    /**
     * Whether the copy in {@link #firstCopyArrivals} is broadcast, indexed by rank.
     */
    @Nullable
    private boolean[] firstCopyBroadcast;

    /**
     * The number of the last sync that visited this event as an ancestor of a tip, see {@link #visit(int)}.
     */
    private int lastVisit;

    /**
     * The number of the last sync that marked this event as a parent of an event to send, see
     * {@link #markAsParentToSend(int)}.
     */
    private int lastMarkedAsParentToSend;

    /**
     * Constructor.
     *
     * @param event     the first submitted instance of the event
     * @param creator   the rank of the node that created the event, or {@code -1}
     * @param nodeCount the number of nodes in the network
     */
    GraphEvent(@NonNull final PlatformEvent event, final int creator, final int nodeCount) {
        this.event = requireNonNull(event);
        this.creator = creator;
        this.knownFrom = new long[nodeCount];
        Arrays.fill(knownFrom, NEVER);
    }

    /**
     * @return the first submitted instance of the event, which must not be modified
     */
    @NonNull
    PlatformEvent event() {
        return event;
    }

    /**
     * @return the rank of the node that created the event, or {@code -1} if that node is not part of the network
     */
    int creator() {
        return creator;
    }

    /**
     * @return the birth round of the event
     */
    long birthRound() {
        return event.getBirthRound();
    }

    /**
     * @return the parents of the event that are in the graph, which the caller must not modify
     */
    @NonNull
    List<GraphEvent> parents() {
        return parents;
    }

    /**
     * @return the events in the graph that have this event as a parent, which the caller must not modify
     */
    @NonNull
    List<GraphEvent> children() {
        return children;
    }

    /**
     * Links a parent to this event, and this event to the parent as its child.
     *
     * @param parent the parent
     */
    void linkParent(@NonNull final GraphEvent parent) {
        parents.add(parent);
        parent.children.add(this);
    }

    /**
     * Unlinks the event from its parents and children, once it is forgotten, so that the events that remain do not
     * keep it.
     */
    void unlink() {
        for (final GraphEvent parent : parents) {
            parent.children.remove(this);
        }
        for (final GraphEvent child : children) {
            child.parents.remove(this);
        }
        parents.clear();
        children.clear();
    }

    /**
     * @param node the rank of a node
     * @return the time the node knows the event from, or {@link #NEVER}
     */
    long knownFrom(final int node) {
        return knownFrom[node];
    }

    /**
     * Reports whether a node knows the event at a given time.
     *
     * @param node the rank of the node
     * @param time the time
     * @return {@code true} if the node knows the event from that time or earlier
     */
    boolean isKnownBy(final int node, final long time) {
        return knownFrom[node] <= time;
    }

    /**
     * Records that a node knows the event from a given time, unless it already does.
     *
     * @param node the rank of the node
     * @param time the time
     */
    void learnedBy(final int node, final long time) {
        if (knownFrom[node] == NEVER) {
            knownFrom[node] = time;
        }
    }

    /**
     * @param node the rank of a node
     * @return the arrival time of the first copy of the event at the node, or {@link #NEVER}
     */
    long firstCopyArrival(final int node) {
        return firstCopyArrivals == null ? NEVER : firstCopyArrivals[node];
    }

    /**
     * @param node the rank of a node
     * @return {@code true} if the first copy of the event at the node is broadcast
     */
    boolean isFirstCopyBroadcast(final int node) {
        return firstCopyBroadcast != null && firstCopyBroadcast[node];
    }

    /**
     * Records the first copy of the event at a node.
     *
     * @param node      the rank of the node
     * @param arrival   the arrival time of the copy
     * @param broadcast whether the copy is broadcast
     */
    void setFirstCopy(final int node, final long arrival, final boolean broadcast) {
        if (firstCopyArrivals == null) {
            firstCopyArrivals = new long[knownFrom.length];
            Arrays.fill(firstCopyArrivals, NEVER);
            firstCopyBroadcast = new boolean[knownFrom.length];
        }
        firstCopyArrivals[node] = arrival;
        firstCopyBroadcast[node] = broadcast;
    }

    /**
     * Marks the event as visited by a sync.
     *
     * @param sync the number of the sync, different for every sync
     * @return {@code true} if the sync had not visited the event yet
     */
    boolean visit(final int sync) {
        if (lastVisit == sync) {
            return false;
        }
        lastVisit = sync;
        return true;
    }

    /**
     * @param sync the number of a sync
     * @return {@code true} if the sync has visited the event, see {@link #visit(int)}
     */
    boolean wasVisited(final int sync) {
        return lastVisit == sync;
    }

    /**
     * Marks the event as a parent of an event a sync sends, or would send if it were old enough.
     *
     * @param sync the number of the sync
     */
    void markAsParentToSend(final int sync) {
        lastMarkedAsParentToSend = sync;
    }

    /**
     * @param sync the number of a sync
     * @return {@code true} if the sync has marked the event as a parent of an event to send
     */
    boolean isMarkedAsParentToSend(final int sync) {
        return lastMarkedAsParentToSend == sync;
    }
}
