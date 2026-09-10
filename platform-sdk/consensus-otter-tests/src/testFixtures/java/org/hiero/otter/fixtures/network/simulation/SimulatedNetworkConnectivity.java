// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
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
     * {@link #addSubmittedEventsToEventLog()}.
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
     * Event windows reported by each node since the previous tick, held until they can be put on the network. Nodes
     * report while they are running concurrently, and only the last window a node reports within a tick is kept, since
     * the ones before it are superseded and would be discarded on arrival anyway.
     *
     * <p>Drained by {@link #scheduleEventWindowsForDelivery(Instant)}. Until then it also serves as the record of
     * whether anything has changed since the last tick, which is what {@link #applyOldestEventWindow()} needs to know.
     */
    private final Map<NodeId, EventWindow> newlyReportedEventWindows = new ConcurrentHashMap<>();

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
     * Events that have been transmitted onto the network but not yet delivered.
     *
     * <p>It relies on the delivery times of a connection being non-decreasing to keep its queues in arrival order,
     * which is what {@link #scheduleEventsForDelivery(Instant)} guarantees with {@link #lastArrivalTimestamps}.
     */
    private final InFlightEvents inFlightEvents = new InFlightEvents(this::isConnected);

    /**
     * Event windows that have been transmitted onto the network but not yet delivered. Relies on the same
     * per-connection ordering as {@link #inFlightEvents}.
     */
    private final InFlightEventWindows inFlightEventWindows = new InFlightEventWindows(this::isConnected);

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
    private final Map<ConnectionKey, Instant> lastArrivalTimestamps = new HashMap<>();

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
    }

    /**
     * Adds a node that is part of this simulated network.
     *
     * <p>Nodes have to be added in a deterministic order to ensure that the simulation is deterministic.
     *
     * @param newNodeId        the id of the node to add
     * @param eventReceiver the event receiver for the node
     */
    public void addNode(@NonNull final NodeId newNodeId, @NonNull final EventReceiver eventReceiver) {
        inFlightEvents.addNode(newNodeId);
        inFlightEventWindows.addNode(newNodeId);
        eventReceivers.put(newNodeId, eventReceiver);
        nodeCursors.put(newNodeId, eventLog.newCursor());
        nodeEventWindows.put(newNodeId, EventWindow.getGenesisEventWindow());
        newlySubmittedEvents.put(newNodeId, new ArrayList<>());
        sortedNodeIds.add(newNodeId);
        Collections.sort(sortedNodeIds);
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
     * <p>The position an event is given in the log decides the order every node's cursor hands it out in, and
     * therefore which jitter value it draws from {@link #random} and when it arrives. Appending as submissions
     * arrived would let the thread scheduler decide all of that and break determinism, so the submissions are
     * held and appended here instead.
     *
     * <p>Which node goes first is drawn from {@link #random} rather than fixed, so that a scenario which always
     * targets the same node - isolating it, or narrowing its bandwidth - is not always paired with the same position
     * in the event log. A fixed order would let one node's events systematically precede another's for a whole run.
     *
     * <p>Shuffling still leaves the result reproducible, because it depends only on {@link #random} and on the sorted
     * node ids it starts from, neither of which is affected by how the nodes interleaved their submissions.
     */
    private void addSubmittedEventsToEventLog() {
        submissionOrder.clear();
        submissionOrder.addAll(sortedNodeIds);
        Collections.shuffle(submissionOrder, random);

        for (final NodeId submitter : submissionOrder) {
            final List<PlatformEvent> events = newlySubmittedEvents.get(submitter);
            for (final PlatformEvent event : events) {
                // The same event is submitted by its creator and again by every node that receives it and re-offers
                // it to gossip. All of those belong in the event log: it records which node transmits an event which
                // is an important part of simulating gossip.
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
        newlyReportedEventWindows.put(nodeId, eventWindow);
    }

    /**
     * Finds the event window of the node that is furthest behind and applies it to the deduplicator and the event log.
     * Everything expired for that node is expired for the whole network, and so is of no further use to anyone.
     *
     * <p>Does nothing when no node has reported a window since the last tick, since the oldest one cannot have moved.
     * Reads {@link #newlyReportedEventWindows} rather than draining it; the draining is done later in the tick by
     * {@link #scheduleEventWindowsForDelivery(Instant)}.
     */
    private void applyOldestEventWindow() {
        if (newlyReportedEventWindows.isEmpty()) {
            return;
        }

        EventWindow oldestEventWindow = null;
        for (final EventWindow eventWindow : nodeEventWindows.values()) {
            if (oldestEventWindow == null || eventWindow.expiredThreshold() < oldestEventWindow.expiredThreshold()) {
                oldestEventWindow = eventWindow;
            }
        }
        if (oldestEventWindow == null) {
            // no nodes have been added yet
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
        // The oldest event window is applied first, so that the window the deduplicator discards expired events by
        // matches the range of birth rounds the log still accepts when the submissions below are appended.
        applyOldestEventWindow();
        addSubmittedEventsToEventLog();
        // Events are delivered before new ones are scheduled, so that an event always spends at least one tick in
        // flight. Scheduling first would let a connection configured with no latency deliver an event in the same
        // tick it was sent, with no simulated time passing in between.
        deliverArrivedEventWindows(now);
        deliverArrivedEvents(now);
        scheduleEventsForDelivery(now);
        scheduleEventWindowsForDelivery(now);
    }

    /**
     * For each node, deliver every event that has arrived at it and can be delivered right now.
     *
     * @param now the current time
     */
    private void deliverArrivedEvents(@NonNull final Instant now) {
        // The order the nodes are delivered to does not need to be randomized. The nodes are not running on any thread
        // when this method is called, and so the order in which nodes are provided events makes no difference.
        for (final NodeId receiverId : sortedNodeIds) {
            inFlightEvents.deliverArrivedEvents(now, eventReceivers.get(receiverId));
        }
    }

    /**
     * For each node, deliver the newest event window each of its peers has landed at it.
     *
     * @param now the current time
     */
    private void deliverArrivedEventWindows(@NonNull final Instant now) {
        for (final NodeId receiverId : sortedNodeIds) {
            inFlightEventWindows.deliverArrivedEventWindows(now, eventReceivers.get(receiverId));
        }
    }

    /**
     * Reports whether the connection from a sender to a receiver is currently up.
     *
     * @param sender   the node at the sending end of the connection
     * @param receiver the node at the receiving end of the connection
     * @return {@code true} if events can travel from the sender to the receiver
     */
    private boolean isConnected(@NonNull final NodeId sender, @NonNull final NodeId receiver) {
        final ConnectionState connectionState = connections.get(new ConnectionKey(sender, receiver));
        return connectionState != null && connectionState.connected();
    }

    /**
     * For each node, walk its cursor over {@link #eventLog} and schedule every event it has not been sent yet for
     * delivery. A node is not sent its own events, nor events that are already ancient for it.
     *
     * <p>When each event arrives is decided by {@link #nextArrivalTime(ConnectionKey, ConnectionState, Instant)}.
     *
     * @param now the current time
     */
    private void scheduleEventsForDelivery(@NonNull final Instant now) {
        if (connections.isEmpty()) {
            return; // No connections have been set, so there is nowhere to send events.
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
                    // The sender and receiver are known to each other, so enqueue the event for delivery. Whether the
                    // connection is up is decided at delivery time rather than here: the cursor is consumed either
                    // way, so an event dropped now would never be offered to this receiver again, and a partition
                    // would permanently deprive it of every event created while the partition was in place.
                    final Instant arrivalTime = nextArrivalTime(connectionKey, connectionState, now);

                    // create a copy so that nodes don't modify each other's events
                    final PlatformEvent eventToDeliver = event.copyGossipedData();
                    eventToDeliver.setSenderId(sender);
                    eventToDeliver.setTimeReceived(arrivalTime);
                    final EventInTransit eventInTransit = new EventInTransit(eventToDeliver, sender, arrivalTime);
                    inFlightEvents.add(receiver, eventInTransit);
                }
            }
        }
    }

    /**
     * Take the event windows reported since the previous tick and schedule each one for delivery to every other node
     * its reporter has a connection to.
     *
     * <p>A node that has not reported since the last tick sends nothing, because its peers already hold the window it
     * would send. Windows travel the same connections as events and draw their arrival times from the same sequence,
     * so a window and an event sent from one node to another arrive in the order they were scheduled.
     *
     * @param now the current time
     */
    private void scheduleEventWindowsForDelivery(@NonNull final Instant now) {
        if (connections.isEmpty()) {
            // No connections have been set, so there is nowhere to send event windows.
            return;
        }

        for (final NodeId sender : sortedNodeIds) {
            final EventWindow eventWindow = newlyReportedEventWindows.remove(sender);
            if (eventWindow == null) {
                // this node has not reported a new window, so its peers are already holding its latest
                continue;
            }

            for (final NodeId receiver : sortedNodeIds) {
                if (receiver.equals(sender)) {
                    continue;
                }

                final ConnectionKey connectionKey = new ConnectionKey(sender, receiver);
                final ConnectionState connectionState = connections.get(connectionKey);
                if (connectionState != null) {
                    // As with events, whether the connection is up is decided at delivery time. A window held through a
                    // partition is superseded by the ones scheduled behind it, so when the connection returns the
                    // receiver is given the sender's current window rather than a stale one.
                    final Instant arrivalTime = nextArrivalTime(connectionKey, connectionState, now);
                    inFlightEventWindows.add(receiver, new EventWindowInTransit(eventWindow, sender, arrivalTime));
                }
            }
        }
    }

    /**
     * Picks the time something scheduled onto a connection now will arrive at the other end.
     *
     * <p>The time is the current time plus the connection's latency, offset by a jitter value drawn from a truncated
     * Gaussian distribution. Jitter can pull an arrival time earlier than one already scheduled on the same connection,
     * so the result is clamped to be no earlier than the previous one. That clamp is what lets {@link InFlightEvents}
     * and {@link InFlightEventWindows} treat each connection's queue as being in arrival order.
     *
     * @param connectionKey   the connection being scheduled onto
     * @param connectionState the latency and jitter of that connection
     * @param now             the current time
     * @return the time of arrival at the receiving end
     */
    @NonNull
    private Instant nextArrivalTime(
            @NonNull final ConnectionKey connectionKey,
            @NonNull final ConnectionState connectionState,
            @NonNull final Instant now) {

        // Simulate network latency and jitter using truncated Gaussian distribution
        final double sigma = connectionState.latency().toNanos() * connectionState.jitter().value / 100.0;
        final double jitter = Math.clamp(random.nextGaussian() * sigma, -3 * sigma, 3 * sigma);
        Instant arrivalTime = now.plus(connectionState.latency()).plusNanos((long) jitter);

        // Ensure arrival time is always incremental
        final Instant lastArrivalTime = lastArrivalTimestamps.getOrDefault(connectionKey, Instant.MIN);
        if (arrivalTime.isBefore(lastArrivalTime)) {
            arrivalTime = lastArrivalTime.plusNanos(1L);
        }
        lastArrivalTimestamps.put(connectionKey, arrivalTime);

        return arrivalTime;
    }

    /**
     * Reset the cursor for a node to the beginning of the event log. This is useful for testing scenarios where a node
     * is restarted and needs to reprocess all events from the beginning.
     *
     * @param nodeId the id of the node whose cursor should be reset
     */
    public void resetCursor(@NonNull final NodeId nodeId) {
        nodeCursors.get(nodeId).seekToFirst();
        inFlightEvents.clearIncoming(nodeId);
        inFlightEventWindows.clearIncoming(nodeId);
    }
}
