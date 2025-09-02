// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle.gossip;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;

import com.hedera.hapi.node.state.roster.Roster;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.Random;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.AddressBook;
import org.hiero.consensus.roster.RosterUtils;
import org.hiero.otter.fixtures.internal.network.ConnectionKey;
import org.hiero.otter.fixtures.network.Topology.ConnectionData;

/**
 * Connects {@link SimulatedGossip} peers in a simulated network.
 * <p>
 * This gossip simulation is intentionally simplistic. It does not attempt to mimic any real gossip algorithm in any
 * meaningful way and makes no attempt to reduce the rate of duplicate events.
 */
@SuppressWarnings("removal")
public class SimulatedNetwork {

    /**
     * The random number generator to use for simulating network delays.
     */
    private final Random random;

    /**
     * Events that have been submitted within the most recent tick. It is safe for multiple nodes to add to their list
     * of submitted events in parallel.
     */
    private final Map<NodeId, List<PlatformEvent>> newlySubmittedEvents = new HashMap<>();

    /**
     * A sorted list of node IDs for when deterministic iteration order is required.
     */
    final List<NodeId> sortedNodeIds = new ArrayList<>();

    /**
     * Events that are currently in transit between nodes in the network.
     */
    private final Map<NodeId, PriorityQueue<EventInTransit>> eventsInTransit = new HashMap<>();

    /**
     * The gossip "component" for each node in the network.
     */
    private final Map<NodeId, SimulatedGossip> gossipInstances = new HashMap<>();

    private final Map<GossipConnectionKey, ConnectionData> connections = new HashMap<>();

    private final Map<GossipConnectionKey, Instant> lastDeliveryTimestamps = new HashMap<>();

    /**
     * Constructor.
     *
     * @param random the random number generator to use for simulating network delays
     * @param roster the roster of the network
     */
    public SimulatedNetwork(@NonNull final Random random, @NonNull final Roster roster) {
        this(
                random,
                roster.rosterEntries().stream()
                        .map(RosterUtils::getNodeId)
                        .sorted()
                        .toList());
    }

    /**
     * Constructor.
     *
     * @param random the random number generator to use for simulating network delays
     * @param addressBook the address book of the network
     */
    public SimulatedNetwork(@NonNull final Random random, @NonNull final AddressBook addressBook) {
        this(random, addressBook.getNodeIdSet().stream().sorted().toList());
    }

    private SimulatedNetwork(@NonNull final Random random, @NonNull final List<NodeId> nodeIds) {

        this.random = requireNonNull(random);

        for (final NodeId nodeId : nodeIds) {
            newlySubmittedEvents.put(nodeId, new ArrayList<>());
            sortedNodeIds.add(nodeId);
            eventsInTransit.put(nodeId, new PriorityQueue<>());
            gossipInstances.put(nodeId, new SimulatedGossip(this, nodeId));
        }
    }

    /**
     * Set the connection data for this simulated network.
     *
     * @param newConnections the connection data
     */
    public void setConnections(@NonNull final Map<ConnectionKey, ConnectionData> newConnections) {
        this.connections.clear();
        this.connections.putAll(newConnections.entrySet().stream()
                .collect(toMap(entry -> GossipConnectionKey.of(entry.getKey()), Entry::getValue)));
    }

    /**
     * Get the gossip instance for a given node.
     *
     * @param nodeId the id of the node
     * @return the gossip instance for the node
     */
    @NonNull
    public SimulatedGossip getGossipInstance(@NonNull final NodeId nodeId) {
        return gossipInstances.get(nodeId);
    }

    /**
     * Submit an event to be gossiped around the network. Safe to be called by multiple nodes in parallel.
     *
     * @param submitterId the id of the node submitting the event
     * @param event the event to gossip
     */
    public void submitEvent(@NonNull final NodeId submitterId, @NonNull final PlatformEvent event) {
        newlySubmittedEvents.get(submitterId).add(event);
    }

    /**
     * Move time forward to the given instant.
     *
     * @param now the new time
     */
    public void tick(@NonNull final Instant now) {
        deliverEvents(now);
        transmitEvents(now);
    }

    /**
     * For each node, deliver all events that are eligible for immediate delivery.
     */
    private void deliverEvents(@NonNull final Instant now) {
        // Iteration order does not need to be deterministic. The nodes are not running on any thread
        // when this method is called, and so the order in which nodes are provided events makes no difference.
        for (final Map.Entry<NodeId, PriorityQueue<EventInTransit>> entry : eventsInTransit.entrySet()) {
            final NodeId nodeId = entry.getKey();
            final PriorityQueue<EventInTransit> events = entry.getValue();

            final Iterator<EventInTransit> iterator = events.iterator();
            while (iterator.hasNext()) {
                final EventInTransit event = iterator.next();

                final GossipConnectionKey connectionKey = new GossipConnectionKey(event.sender(), nodeId);
                final ConnectionData connectionData = connections.get(connectionKey);
                if (connectionData == null || !connectionData.connected()) {
                    // No connection between sender and receiver, so skip delivery of this event
                    continue;
                }

                if (event.arrivalTime().isAfter(now)) {
                    // no more events to deliver
                    break;
                }

                iterator.remove();
                gossipInstances.get(nodeId).receiveEvent(event.event());
            }
        }
    }

    /**
     * For each node, take the events that were submitted within the last tick and "transmit them over the network".
     *
     * @param now the current time
     */
    private void transmitEvents(@NonNull final Instant now) {
        if (connections.isEmpty()) {
            return; // No connections have been set, so we cannot transmit events.
        }

        // Transmission order of the loops in this method must be deterministic, else nodes may receive events
        // in nondeterministic orders with nondeterministic timing.

        for (final NodeId sender : sortedNodeIds) {
            final List<PlatformEvent> events = newlySubmittedEvents.get(sender);
            for (final PlatformEvent event : events) {
                for (final NodeId receiver : sortedNodeIds) {
                    if (sender.equals(receiver)) {
                        // Don't gossip to ourselves
                        continue;
                    }

                    final GossipConnectionKey connectionKey = new GossipConnectionKey(sender, receiver);
                    final ConnectionData connectionData = connections.get(connectionKey);

                    Instant deliveryTime;
                    if (connectionData == null) {
                        // No connection between sender and receiver. We must still enqueue the event in case the
                        // nodes become connected later.
                        deliveryTime = lastDeliveryTimestamps
                                .getOrDefault(connectionKey, Instant.MIN)
                                .plusNanos(1);
                    } else {
                        // Simulate network latency and jitter using truncated Gaussian distribution
                        final double sigma = connectionData.latency().toNanos() * connectionData.jitter().value / 100.0;
                        final double jitter = Math.clamp(random.nextGaussian() * sigma, -3 * sigma, 3 * sigma);
                        deliveryTime = now.plus(connectionData.latency()).plusNanos((long) jitter);
                    }

                    // Ensure delivery time is always incremental
                    final Instant lastDeliveryTime = lastDeliveryTimestamps.getOrDefault(connectionKey, Instant.MIN);
                    if (deliveryTime.isBefore(lastDeliveryTime)) {
                        deliveryTime = lastDeliveryTime.plusNanos(1L);
                    }
                    lastDeliveryTimestamps.put(connectionKey, deliveryTime);

                    // create a copy so that nodes don't modify each other's events
                    final PlatformEvent eventToDeliver = event.copyGossipedData();
                    eventToDeliver.setSenderId(sender);
                    eventToDeliver.setTimeReceived(deliveryTime);
                    final EventInTransit eventInTransit = new EventInTransit(eventToDeliver, sender, deliveryTime);
                    eventsInTransit.get(receiver).add(eventInTransit);
                }
            }
            events.clear();
        }
    }

    // Can be removed if we are able to clean up NodeId usage
    // https://github.com/hiero-ledger/hiero-consensus-node/issues/20537
    private record GossipConnectionKey(@NonNull NodeId sender, @NonNull NodeId receiver) {

        static GossipConnectionKey of(@NonNull final ConnectionKey key) {
            return new GossipConnectionKey(
                    NodeId.of(key.sender().id()), NodeId.of(key.receiver().id()));
        }
    }
}
