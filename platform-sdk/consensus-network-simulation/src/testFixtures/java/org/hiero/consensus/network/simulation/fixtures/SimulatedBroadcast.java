// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.network.simulation.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.Set;
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
    private final Map<NodeId, List<PlatformEvent>> eventsDelivered;

    private final Map<ConnectionKey, ConnectionInfo> connections = new HashMap<>();
    private final List<NodeId> nodes;

    Instant now;

    public SimulatedBroadcast(final Instant now, final int numNodes) {
        this.now = now;
        this.nodes = LongStream.range(0, numNodes).mapToObj(NodeId::of).toList();
        eventsInTransit = nodes.stream().collect(Collectors.toMap(Function.identity(),
                _ -> new PriorityQueue<>()));
        eventsDelivered = nodes.stream().collect(Collectors.toMap(Function.identity(),
                _ -> new LinkedList<>()));
    }

    public SimulatedBroadcast(final Instant now, final List<NodeId> nodes) {
        this.now = now;
        this.nodes = nodes.stream().toList();
        eventsInTransit = nodes.stream().collect(Collectors.toMap(Function.identity(),
                _ -> new PriorityQueue<>()));
        eventsDelivered = nodes.stream().collect(Collectors.toMap(Function.identity(),
                _ -> new LinkedList<>()));
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
            eventToDeliver.setNGen(event.getNGen());
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

    public List<PlatformEvent> getDeliveredEvents(final NodeId nodeId) {
        return eventsDelivered.replace(nodeId, new LinkedList<>());
    }

    /**
     * Sets the same latency for all connections in the network.
     *
     * @param latency the latency to apply to every connection
     */
    public void setUniformLatency(@NonNull final Duration latency) {
        for (final NodeId sender : nodes) {
            for (final NodeId receiver : nodes) {
                if (!sender.equals(receiver)) {
                    connections.put(new ConnectionKey(sender, receiver), new ConnectionInfo(latency));
                }
            }
        }
    }

    public void setLatency(final NetworkLatency latency){
        for (int i = 0; i < nodes.size(); i++) {
            for (int j = 0; j < nodes.size(); j++) {
                if (i == j) {
                    continue;
                }
                connections.put(
                        new ConnectionKey(nodes.get(i), nodes.get(j)),
                        new ConnectionInfo(latency.getLatency(i, j)));
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
