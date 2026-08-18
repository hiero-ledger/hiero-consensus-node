// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.internal.network.ConnectionKey;
import org.hiero.otter.fixtures.network.Topology.ConnectionState;
import org.hiero.otter.fixtures.turtle.gossip.SimulatedGossip;
import org.hiero.otter.fixtures.util.CursoredLog;
import org.hiero.otter.fixtures.util.CursoredLog.Cursor;

/**
 * Connects {@link SimulatedGossip} peers in a simulated network.
 * <p>
 * This gossip simulation is intentionally simplistic. It does not attempt to mimic any real gossip algorithm in any
 * meaningful way and makes no attempt to reduce the rate of duplicate events.
 */
public class SimulatedNetworkConnectivity {

    /**
     * The random number generator to use for simulating network delays.
     */
    private final Random random;

    private final CursoredLog<PlatformEvent> eventLog;

    private final Map<NodeId, Cursor<PlatformEvent>> nodeCursors = new HashMap<>();

    /**
     * The most recent {@link EventWindow} reported by each node. A node's window determines which events are worth
     * transmitting to it, and the oldest window across all nodes determines what can be pruned from {@link #eventLog}.
     */
    private final Map<NodeId, EventWindow> nodeEventWindows = new ConcurrentHashMap<>();

    /**
     * Recognizes events that have already been submitted, so that each one is added to {@link #eventLog} exactly once.
     */
    private final EventDeduplicator deduplicator = new EventDeduplicator();

    /**
     * The highest birth round that has been pruned from {@link #eventLog}. Birth rounds must be pruned in strictly
     * increasing order, so this records how far the pruning has already progressed.
     */
    private long lastPrunedBirthRound = ConsensusConstants.ROUND_FIRST - 1;

    /**
     * Set when a node reports a new event window. Pruning is deferred to the next tick so that the windows reported by
     * all of the nodes for a given round are accounted for by a single pass over {@link #nodeEventWindows}.
     */
    private volatile boolean eventWindowsChanged = false;

    /**
     * Events that are currently in transit between nodes in the network, keyed by the node that will receive the
     * event.
     */
    private final Map<NodeId, PriorityQueue<EventInTransit>> eventsInTransit = new HashMap<>();

    /**
     * The gossip "component" for each node in the network.
     */
    private final Map<NodeId, EventReceiver> eventReceivers = new HashMap<>();

    private final Map<ConnectionKey, ConnectionState> connections = new HashMap<>();

    private final Map<ConnectionKey, Instant> lastDeliveryTimestamps = new HashMap<>();

    /**
     * Constructor.
     *
     * @param random the random number generator to use for simulating network delays
     */
    public SimulatedNetworkConnectivity(@NonNull final Random random) {
        this.random = requireNonNull(random);
        // TODO create static constants, or derive them from default configurations like roundExpired
        eventLog = new CursoredLog<>(
                ConsensusConstants.ROUND_FIRST, 1000, (int) Math.pow(2, 8), PlatformEvent::getBirthRound);
    }

    /**
     * Adds a node that is part of this simulated network.
     *
     * <p>Nodes have to be added in a deterministic order to ensure that the simulation is deterministic.
     *
     * @param nodeId        the id of the node
     * @param eventReceiver the event receiver for the node
     */
    public void addNode(@NonNull final NodeId nodeId, @NonNull final EventReceiver eventReceiver) {
        eventsInTransit.put(nodeId, new PriorityQueue<>());
        eventReceivers.put(nodeId, eventReceiver);
        nodeCursors.put(nodeId, eventLog.newCursor());
        nodeEventWindows.put(nodeId, EventWindow.getGenesisEventWindow());
    }

    /**
     * Set the connection data for this simulated network.
     *
     * @param newConnections the connection data
     */
    public void setConnections(@NonNull final Map<ConnectionKey, ConnectionState> newConnections) {
        this.connections.clear();
        this.connections.putAll(newConnections);
    }

    /**
     * Submit an event to be gossiped around the network. Safe to be called by multiple nodes in parallel.
     *
     * @param event the event to gossip
     */
    public void submitEvent(@NonNull final PlatformEvent event) {
        // Some simulated nodes could re-offer an event to gossip once it has been through intake, so the same
        // event arrives here once from its creator and again from each node that received it. Only the first submission
        // is logged.
        if (deduplicator.isDuplicate(event)) {
            return;
        }
        eventLog.add(event);
    }

    /**
     * Report the latest {@link EventWindow} of a node. Safe to be called by multiple nodes in parallel.
     *
     * @param nodeId      the id of the node the event window belongs to
     * @param eventWindow the node's latest event window
     */
    public void updateEventWindow(@NonNull final NodeId nodeId, @NonNull final EventWindow eventWindow) {
        nodeEventWindows.put(nodeId, eventWindow);
        eventWindowsChanged = true;
    }

    /**
     * Finds the event window of the node that is furthest behind and applies it to the deduplicator and the event log.
     * Everything expired for that node is expired for the whole network, and so is of no further use to anyone.
     */
    private void applyOldestEventWindow() {
        if (!eventWindowsChanged) {
            return;
        }
        eventWindowsChanged = false;

        EventWindow oldestEventWindow = null;
        for (final EventWindow eventWindow : nodeEventWindows.values()) {
            if (oldestEventWindow == null || eventWindow.expiredThreshold() < oldestEventWindow.expiredThreshold()) {
                oldestEventWindow = eventWindow;
            }
        }
        if (oldestEventWindow == null) {
            return; // no nodes have been added yet
        }

        deduplicator.setOldestEventWindow(oldestEventWindow);

        // An event is expired for a node when its birth round is strictly below that node's expired threshold,
        // so the highest birth round that is expired for all of them is one below the lowest threshold.
        final long pruneThroughBirthRound = oldestEventWindow.expiredThreshold() - 1;
        if (pruneThroughBirthRound > lastPrunedBirthRound) {
            eventLog.removeSequenceNumber(pruneThroughBirthRound);
            lastPrunedBirthRound = pruneThroughBirthRound;
        }
    }

    /**
     * Move time forward to the given instant.
     *
     * @param now the new time
     */
    public void tick(@NonNull final Instant now) {
        applyOldestEventWindow();
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

                final ConnectionKey connectionKey = new ConnectionKey(event.sender(), nodeId);
                final ConnectionState connectionState = connections.get(connectionKey);
                if (connectionState == null || !connectionState.connected()) {
                    // No connection between sender and receiver, so skip delivery of this event
                    continue;
                }

                if (event.arrivalTime().isAfter(now)) {
                    // no more events to deliver
                    break;
                }

                // only remove the event from the buffer if it was successfully delivered
                if (eventReceivers.get(nodeId).receiveEvent(event.event())) {
                    iterator.remove();
                } else {
                    break;
                }
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

        for (final Entry<NodeId, Cursor<PlatformEvent>> entry : nodeCursors.entrySet()) {
            final NodeId receiver = entry.getKey();
            final Cursor<PlatformEvent> cursor = entry.getValue();
            final EventWindow receiverEventWindow = nodeEventWindows.get(receiver);

            while (cursor.hasNext()) {
                final PlatformEvent event = cursor.next();
                final NodeId sender = event.getSenderId();
                assert sender != null;

                // Don't send a node's own events back to it
                if (receiver.equals(sender)) {
                    continue;
                }

                // The receiver would discard this event on arrival, so there is no point in transmitting it
                if (receiverEventWindow.isAncient(event)) {
                    continue;
                }

                final ConnectionKey connectionKey = new ConnectionKey(sender, receiver);
                final ConnectionState connectionState = connections.get(connectionKey);
                if (connectionState != null) {
                    // There is an active connection between sender and receiver. Enqueue the event for delivery.

                    // Simulate network latency and jitter using truncated Gaussian distribution
                    final double sigma = connectionState.latency().toNanos() * connectionState.jitter().value / 100.0;
                    final double jitter = Math.clamp(random.nextGaussian() * sigma, -3 * sigma, 3 * sigma);
                    Instant deliveryTime = now.plus(connectionState.latency()).plusNanos((long) jitter);

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
        }
    }
}
