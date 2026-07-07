// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.network.simulation.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;

/**
 * This gossip simulation is intentionally simplistic. It does not attempt to mimic any real gossip algorithm in any
 * meaningful way and makes no attempt to reduce the rate of duplicate events.
 */
public class SimulatedBroadcast {

    /**
     * Events that are currently in transit between nodes in the network.
     */
    private final Map<NodeId, PriorityQueue<EventInTransit>> eventsInTransit;

    /** Events that have been delivered to each node but not yet consumed via {@link #getDeliveredEvents}. */
    private final Map<NodeId, List<PlatformEvent>> eventsDelivered;

    /** Per-connection latency configuration, keyed by ordered (sender, receiver) pair. */
    private final Map<ConnectionKey, ConnectionInfo> connections = new HashMap<>();

    /** Ordered list of all node IDs participating in the network. */
    private final List<NodeId> nodes;

    /** The current simulated time, advanced by each call to {@link #tick}. */
    Instant now;

    /**
     * Creates a new broadcast simulation with sequentially assigned node IDs from {@code 0} to
     * {@code numNodes - 1}.
     *
     * @param now      the initial simulated time
     * @param numNodes the number of nodes in the network
     */
    public SimulatedBroadcast(final Instant now, final int numNodes) {
        this.now = now;
        this.nodes = LongStream.range(0, numNodes).mapToObj(NodeId::of).toList();
        eventsInTransit = nodes.stream().collect(Collectors.toMap(Function.identity(), _ -> new PriorityQueue<>()));
        eventsDelivered = nodes.stream().collect(Collectors.toMap(Function.identity(), _ -> new LinkedList<>()));
    }

    /**
     * Creates a new broadcast simulation with the given set of node IDs.
     *
     * @param now   the initial simulated time
     * @param nodes the node IDs to include in the network
     */
    public SimulatedBroadcast(final Instant now, final List<NodeId> nodes) {
        this.now = now;
        this.nodes = nodes.stream().toList();
        eventsInTransit = nodes.stream().collect(Collectors.toMap(Function.identity(), _ -> new PriorityQueue<>()));
        eventsDelivered = nodes.stream().collect(Collectors.toMap(Function.identity(), _ -> new LinkedList<>()));
    }

    /**
     * Submit an event to be gossiped around the network.
     *
     * @param event the event to gossip
     */
    public void submitEvent(@NonNull final PlatformEvent event) {
        final NodeId sender = event.getCreatorId();
        for (final NodeId receiver : nodes) {
            if (sender.equals(receiver)) {
                // Don't gossip to ourselves
                continue;
            }

            final ConnectionKey connectionKey = new ConnectionKey(sender, receiver);
            final ConnectionInfo connectionState = connections.getOrDefault(connectionKey, ConnectionInfo.DEFAULT);

            final Instant deliveryTime = now.plus(connectionState.latency());

            // create a copy so that nodes don't modify each other's events
            final PlatformEvent eventToDeliver = event.copyGossipedData();
            eventToDeliver.setSenderId(sender);
            eventToDeliver.setTimeReceived(deliveryTime);
            eventToDeliver.setSequenceNumber(event.getSequenceNumber());
            final EventInTransit eventInTransit = new EventInTransit(eventToDeliver, deliveryTime);
            eventsInTransit.get(receiver).add(eventInTransit);
        }
    }

    /**
     * Move time forward to the given instant.
     *
     * @param now the new time
     */
    public void tick(@NonNull final Instant now) {
        this.now = now;
        deliverEvents();
    }

    /**
     * Returns and drains the list of events that have been delivered to the given node since the last call.
     * Subsequent calls will return an empty list until new events are delivered.
     *
     * @param nodeId the node whose delivered events to retrieve
     * @return the events delivered to the node since the last call, in arrival-time order
     */
    public List<PlatformEvent> getDeliveredEvents(final NodeId nodeId) {
        return eventsDelivered.replace(nodeId, new LinkedList<>());
    }

    /**
     * Applies a {@link NetworkLatency} configuration to all directed connections in the network.
     *
     * @param latency the latency model to apply
     */
    public void setLatency(final NetworkLatency latency) {
        for (int i = 0; i < nodes.size(); i++) {
            for (int j = 0; j < nodes.size(); j++) {
                if (i == j) {
                    continue;
                }
                connections.put(
                        new ConnectionKey(nodes.get(i), nodes.get(j)), new ConnectionInfo(latency.getLatency(i, j)));
            }
        }
    }

    /**
     * For each node, deliver all events that are eligible for immediate delivery.
     */
    private void deliverEvents() {
        // Iteration order does not need to be deterministic. The nodes are not running on any thread
        // when this method is called, and so the order in which nodes are provided events makes no difference.
        for (final Entry<NodeId, PriorityQueue<EventInTransit>> entry : eventsInTransit.entrySet()) {
            final NodeId nodeId = entry.getKey();
            final PriorityQueue<EventInTransit> events = entry.getValue();

            while (!events.isEmpty() && !events.peek().arrivalTime().isAfter(now)) {
                eventsDelivered.get(nodeId).add(events.poll().event());
            }
        }
    }
}
