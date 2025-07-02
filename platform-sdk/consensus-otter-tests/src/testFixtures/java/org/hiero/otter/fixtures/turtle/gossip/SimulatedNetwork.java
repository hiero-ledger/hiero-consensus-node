// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle.gossip;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.roster.Roster;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.AddressBook;
import org.hiero.consensus.roster.RosterUtils;

/**
 * Connects {@link SimulatedGossip} peers in a simulated network.
 * <p>
 * This gossip simulation is intentionally simplistic. It does not attempt to mimic any real gossip algorithm in any
 * meaningful way and makes no attempt to reduce the rate of duplicate events.
 */
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

    /**
     * The average delay for events to travel between nodes, in nanoseconds.
     */
    private final long averageDelayNanos;

    /**
     * The standard deviation of the delay for events to travel between nodes, in nanoseconds.
     */
    private final long standardDeviationDelayNanos;

    /**
     * Constructor.
     *
     * @param random                 the random number generator to use for simulating network delays
     * @param roster                 the roster of the network
     * @param averageDelay           the average delay for events to travel between nodes
     * @param standardDeviationDelay the standard deviation of the delay for events to travel between nodes
     */
    public SimulatedNetwork(
            @NonNull final Random random,
            @NonNull final Roster roster,
            @NonNull final Duration averageDelay,
            @NonNull final Duration standardDeviationDelay) {
        this(
                random,
                roster.rosterEntries().stream()
                        .map(RosterUtils::getNodeId)
                        .sorted()
                        .toList(),
                averageDelay,
                standardDeviationDelay);
    }

    /**
     * Constructor.
     *
     * @param random                 the random number generator to use for simulating network delays
     * @param addressBook            the address book of the network
     * @param averageDelay           the average delay for events to travel between nodes
     * @param standardDeviationDelay the standard deviation of the delay for events to travel between nodes
     */
    public SimulatedNetwork(
            @NonNull final Random random,
            @NonNull final AddressBook addressBook,
            @NonNull final Duration averageDelay,
            @NonNull final Duration standardDeviationDelay) {
        this(random, addressBook.getNodeIdSet().stream().sorted().toList(), averageDelay, standardDeviationDelay);
    }

    private SimulatedNetwork(
            @NonNull final Random random,
            @NonNull final List<NodeId> nodeIds,
            @NonNull final Duration averageDelay,
            @NonNull final Duration standardDeviationDelay) {

        this.random = requireNonNull(random);

        for (final NodeId nodeId : nodeIds) {
            newlySubmittedEvents.put(nodeId, new ArrayList<>());
            sortedNodeIds.add(nodeId);
            eventsInTransit.put(nodeId, new PriorityQueue<>());
            gossipInstances.put(nodeId, new SimulatedGossip(this, nodeId));
        }

        this.averageDelayNanos = averageDelay.toNanos();
        this.standardDeviationDelayNanos = standardDeviationDelay.toNanos();
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
     * @param event       the event to gossip
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

                    final PriorityQueue<EventInTransit> receiverEvents = eventsInTransit.get(receiver);

                    final Instant deliveryTime = now.plusNanos(
                            (long) (averageDelayNanos + random.nextGaussian() * standardDeviationDelayNanos));

                    // create a copy so that nodes don't modify each other's events
                    final PlatformEvent eventToDeliver = event.copyGossipedData();
                    eventToDeliver.setSenderId(sender);
                    eventToDeliver.setTimeReceived(deliveryTime);
                    final EventInTransit eventInTransit = new EventInTransit(eventToDeliver, sender, deliveryTime);
                    receiverEvents.add(eventInTransit);
                }
            }
            events.clear();
        }
    }
}
