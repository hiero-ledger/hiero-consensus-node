// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.roster.Roster;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
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
 * This gossip simulation is more simplistic than real gossip, but behaves in similar ways. The goal is not a perfect
 * mimic of real gossip behavior, but a rough approximation while being highly performant.
 *
 * <p>A node that drifts far enough behind its peers is detected as having fallen behind, at which point the network
 * stops sending it events. This mirrors what happens to a real node, which halts gossip and event creation when it
 * detects it has fallen behind and waits to reconnect. Nothing here brings such a node back yet, so it stays behind
 * for the rest of the run. See {@link #hasFallenBehind(NodeId)}.
 */
public class SimulatedNetworkConnectivity {

    /**
     * The initial capacity of the sequence number space for the cursored log of events. This should match the number of
     * non-expired rounds according to the default configuration of the nodes in the network, but it does not have to be
     * exact because the log will expand as needed.
     */
    private static final int INITIAL_SEQUENCE_NUMBER_CAPACITY = 1000;

    /**
     * The initial capacity of the event log. This should be large enough to hold all events that are not expired, but
     * it does not have to be exact because the log will expand as needed. Must be a power of 2.
     */
    private static final int INITIAL_EVENT_LOG_CAPACITY = (int) Math.pow(2, 8);

    /**
     * The random number generator to use for simulating network delays.
     */
    private final Random random;

    /**
     * A cursored log of unique events used for gossiping events between nodes. Each node has its own cursor into this
     * log, which is used to determine which events to transmit to that node. The log is pruned based on the event
     * windows reported by the nodes, so that events that are no longer needed by any node are removed from the log.
     */
    private final CursoredLog<PlatformEvent> eventLog;

    /**
     * A map of cursors for each node in the network used to determine which events the node receives nest.
     */
    private final Map<NodeId, Cursor<PlatformEvent>> nodeCursors = new HashMap<>();

    /**
     * Events submitted by each node since the previous tick, held until they can be added to {@link #eventLog}. Nodes
     * submit while they are running concurrently, so each node appends only to its own list and no node observes the
     * submissions of another. The iteration order of this map is not relied upon; {@link #submissionOrder} decides the
     * order the lists are drained in.
     */
    private final Map<NodeId, List<PlatformEvent>> newlySubmittedEvents = new ConcurrentHashMap<>();

    /**
     * Every node in the network, in node id order. Maintained as nodes are added rather than being sorted on each tick,
     * and never reordered, so that it can serve as the canonical starting point for the shuffle in
     * {@link #addSubmittedEventsToLog()}.
     */
    private final List<NodeId> sortedNodeIds = new ArrayList<>();

    /**
     * The order {@link #newlySubmittedEvents} is drained in on the current tick. A shuffled copy of
     * {@link #sortedNodeIds}, reused across ticks so that producing it does not allocate.
     */
    private final List<NodeId> submissionOrder = new ArrayList<>();

    /**
     * The most recent {@link EventWindow} reported by each node. A node's window determines which events are worth
     * transmitting to it, and the oldest window across all nodes determines what can be pruned from {@link #eventLog}.
     */
    private final Map<NodeId, EventWindow> nodeEventWindows = new ConcurrentHashMap<>();

    /**
     * Set when a node reports a new event window. Pruning is deferred to the next tick so that the windows reported by
     * all the nodes for a given round are accounted for by a single pass over {@link #nodeEventWindows}.
     */
    private volatile boolean eventWindowsChanged = false;

    /**
     * Decides which nodes have fallen behind the rest of the network. Reads {@link #sortedNodeIds},
     * {@link #nodeEventWindows} and {@link #connections}; a node it reports is sent no further events, has its
     * submissions discarded, and stops holding back the pruning of {@link #eventLog}.
     */
    private final FallenBehindDetector fallenBehindDetector;

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
     * Events that are currently in transit between nodes in the network, keyed by the node that will receive the
     * event.
     */
    private final Map<NodeId, PriorityQueue<EventInTransit>> eventsInTransit = new HashMap<>();

    /**
     * The gossip "component" for each node in the network.
     */
    private final Map<NodeId, EventReceiver> eventReceivers = new HashMap<>();

    /**
     * A map containing the connection state between each pair of nodes in the network. Used to determine if and when an
     * event should be delivered to a particular node.
     */
    private final Map<ConnectionKey, ConnectionState> connections = new HashMap<>();

    /**
     * The last time an event was delivered from a sender to a receiver. Used to ensure that events are delivered in
     * strictly increasing order of time, even when network jitter is applied.
     */
    private final Map<ConnectionKey, Instant> lastDeliveryTimestamps = new HashMap<>();

    /**
     * Constructor.
     *
     * @param random the random number generator to use for simulating network delays
     */
    public SimulatedNetworkConnectivity(@NonNull final Random random) {
        this.random = requireNonNull(random);
        eventLog = new CursoredLog<>(
                ConsensusConstants.ROUND_FIRST,
                INITIAL_SEQUENCE_NUMBER_CAPACITY,
                INITIAL_EVENT_LOG_CAPACITY,
                PlatformEvent::getBirthRound);
        fallenBehindDetector = new FallenBehindDetector(sortedNodeIds, nodeEventWindows, connections);
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
        newlySubmittedEvents.put(nodeId, new ArrayList<>());
        sortedNodeIds.add(nodeId);
        Collections.sort(sortedNodeIds);
    }

    /**
     * Provides the data that is not available when this object is constructed, which enables fallen behind detection.
     * Until this is called, no node is ever considered to have fallen behind.
     *
     * @param roster                the roster of the network, used to weigh the reports that a node has fallen behind
     * @param fallenBehindThreshold the fraction of the peer weight that has to report a node as behind before it is
     *                              considered to have fallen behind
     */
    public void start(@NonNull final Roster roster, final double fallenBehindThreshold) {
        fallenBehindDetector.start(roster, fallenBehindThreshold);
    }

    /**
     * Set the connection data for this simulated network.
     *
     * @param newConnections the connection data
     */
    public void setConnections(@NonNull final Map<ConnectionKey, ConnectionState> newConnections) {
        this.connections.clear();
        this.connections.putAll(newConnections);
        // A node only learns a peer's event window by syncing with it, so which peers are reachable decides which of
        // them can report the node as behind.
        fallenBehindDetector.markStale();
    }

    /**
     * Submit an event to be gossiped around the network. Safe to be called by multiple nodes in parallel.
     *
     * <p>The event is not added to {@link #eventLog} here. Nodes run concurrently while they submit, so the order
     * submissions arrive in is decided by the thread scheduler, and the log has to be ordered reproducibly. The event
     * is held until the next call to {@link #tick(Instant)}, which does the appending on a single thread.
     *
     * @param event the event to gossip, with its sender set to the node submitting it
     */
    public void submitEvent(@NonNull final PlatformEvent event) {
        final NodeId submitter = requireNonNull(event.getSenderId(), "a submitted event must have its sender set");
        newlySubmittedEvents.get(submitter).add(event);
    }

    /**
     * Adds the events submitted since the previous tick to {@link #eventLog}, one node at a time, and within a node in
     * the order it submitted them.
     *
     * <p>The position an event is given in the log decides the order every node's cursor hands it out in, and therefore
     * which jitter value it draws from {@link #random} and when it arrives. Appending as submissions arrived would let
     * the thread scheduler decide all of that and break determinism, so the submissions are held and appended here instead.
     *
     * <p>Which node goes first is drawn from {@link #random} rather than fixed, so that a scenario which always targets
     * the same node - isolating it, or narrowing its bandwidth - is not always paired with the same position in the log.
     * A fixed order would let one node's events systematically precede another's for a whole run. The result is still
     * reproducible: the nodes are shuffled from their sorted order, which never depends on the scheduler, and the
     * shuffle draws {@code nodeCount - 1} times whatever was submitted, so the draws a given seed makes do not depend on
     * how the nodes were interleaved.
     */
    private void addSubmittedEventsToLog() {
        // Shuffling permutes whatever order it is given, so the starting point has to be canonical, or it would carry
        // through to the log. Copying from the sorted node ids gives that without sorting on every tick.
        submissionOrder.clear();
        submissionOrder.addAll(sortedNodeIds);
        Collections.shuffle(submissionOrder, random);

        for (final NodeId submitter : submissionOrder) {
            final List<PlatformEvent> events = newlySubmittedEvents.get(submitter);

            if (fallenBehindDetector.hasFallenBehind(submitter)) {
                // A real node halts event creation once it detects it has fallen behind, so nothing it produces from
                // here on belongs in the log. Discarding it is also what keeps the log from rejecting an add: the
                // submitter's event window is frozen, so it goes on stamping events with a birth round that pruning
                // has already moved past.
                events.clear();
                continue;
            }

            for (final PlatformEvent event : events) {
                // If a node re-offers an event to gossip once it has been through intake, the same event is submitted
                // by its creator and again by every node that receives it. Each of those submissions belongs in the
                // log, because the log records the node an event is transmitted from, but one per submitter is enough.
                if (deduplicator.addIfUnique(event)) {
                    eventLog.add(event);
                }
            }
            events.clear();
        }
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
        fallenBehindDetector.markStale();
    }

    /**
     * Checks whether a node has fallen behind the rest of the network.
     *
     * @param nodeId the id of the node to check
     * @return {@code true} if the node has fallen behind
     */
    public boolean hasFallenBehind(@NonNull final NodeId nodeId) {
        return fallenBehindDetector.hasFallenBehind(nodeId);
    }

    /**
     * Stops the flow of events to the nodes that have just fallen behind.
     */
    private void stopSendingToFallenBehindNodes() {
        for (final NodeId nodeId : fallenBehindDetector.detectNewlyFallenBehind()) {
            // Nothing further will be sent to this node, so what is still on the wire to it would be the last thing it
            // ever received. Dropping it holds the queue at its current size for the rest of the run.
            eventsInTransit.get(nodeId).clear();
        }
    }

    /**
     * Finds the event window of the node that is furthest behind and applies it to the deduplicator and the event log.
     * Everything expired for that node is expired for the whole network, and so is of no further use to anyone.
     *
     * <p>Nodes that have fallen behind are left out. Such a node receives nothing further, so its window never
     * advances again, and letting it decide the threshold would hold every event it is missing in the log for the rest
     * of the run.
     */
    private void applyOldestEventWindow() {
        if (!eventWindowsChanged) {
            return;
        }
        eventWindowsChanged = false;

        EventWindow oldestEventWindow = null;
        for (final Entry<NodeId, EventWindow> entry : nodeEventWindows.entrySet()) {
            if (fallenBehindDetector.hasFallenBehind(entry.getKey())) {
                continue;
            }
            final EventWindow eventWindow = entry.getValue();
            if (oldestEventWindow == null || eventWindow.expiredThreshold() < oldestEventWindow.expiredThreshold()) {
                oldestEventWindow = eventWindow;
            }
        }
        if (oldestEventWindow == null) {
            // no nodes have been added yet, or every node has fallen behind
            return;
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
        // Detection runs ahead of pruning, so that a node found to have fallen behind on this tick stops holding the
        // pruning threshold back on this tick rather than the next one.
        stopSendingToFallenBehindNodes();
        // The oldest event window is applied first, so that the window the deduplicator discards expired events by
        // matches the range of birth rounds the log still accepts when the submissions below are appended.
        applyOldestEventWindow();
        addSubmittedEventsToLog();
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
            if (fallenBehindDetector.hasFallenBehind(nodeId)) {
                // The node has stopped gossiping, so nothing more reaches it
                continue;
            }
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
            if (fallenBehindDetector.hasFallenBehind(receiver)) {
                // Nothing is sent to a node that has fallen behind. Its cursor is deliberately left where it is: the
                // node is not syncing, so it is not passing over these events, it is simply not being offered them.
                continue;
            }
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

    /**
     * Reset the cursor for a node to the beginning of the event log. This is useful for testing scenarios where a node
     * is restarted and needs to reprocess all events from the beginning.
     *
     * @param nodeId the id of the node whose cursor should be reset
     */
    public void resetCursor(@NonNull final NodeId nodeId) {
        nodeCursors.get(nodeId).seekToFirst();
        eventsInTransit.get(nodeId).clear();
    }
}
