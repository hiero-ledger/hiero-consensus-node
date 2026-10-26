// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import static java.util.Objects.requireNonNull;

import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import org.hiero.consensus.gossip.config.BroadcastConfig;
import org.hiero.consensus.gossip.config.SyncConfig;
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
 *
 * <p>The state of the connections is kept in {@link LinkStates}, and the arrival time of everything put onto a
 * connection is picked by {@link ArrivalTimes}. Both identify nodes by their rank, see {@link NodeRanks}, and this class
 * looks up the rank of a node only when the network is set up and when an event enters the network. Times are kept
 * internally as nanoseconds since the start of the simulation.
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
     * The instant the simulation starts at. Times are kept internally as nanoseconds since this instant.
     */
    private final Instant start;

    /**
     * The production broadcast settings the delivery delays are derived from.
     */
    private final BroadcastConfig broadcastConfig;

    /**
     * The production sync settings the delivery delays are derived from.
     */
    private final SyncConfig syncConfig;

    /**
     * The time of the most recent {@link #tick(Instant)}, in nanoseconds since {@link #start}. This is the time that
     * changes to the connections are taken to happen at, and the time that delivery is judged against. Before the
     * first tick it is the start of the simulation.
     */
    private long currentTime = 0;

    /**
     * A cursored log of unique events used for gossiping events between nodes. Each node has its own cursor into this
     * log, which is used to determine which events to transmit to that node. The log is pruned based on the event
     * windows reported by the nodes, so that events that are no longer needed by any node are removed from the log.
     */
    private final CursoredLog<PlatformEvent> eventLog;

    /**
     * The cursor of each node into {@link #eventLog}, indexed by rank, used to determine which events the node receives
     * next.
     */
    private final List<Cursor<PlatformEvent>> nodeCursors = new ArrayList<>();

    /**
     * Events submitted by each node since the previous tick, held until they can be added to {@link #eventLog}. Nodes
     * submit while they are running concurrently, so each node appends only to its own list and no node observes the
     * submissions of another. The iteration order of this map is not relied upon; {@link #submissionOrder} decides the
     * order the lists are drained in.
     */
    private final Map<NodeId, List<PlatformEvent>> newlySubmittedEvents = new ConcurrentHashMap<>();

    /**
     * The nodes of the network and their ranks. Set once by {@link #setUp(Collection)}; the node id order they define
     * serves as the canonical starting point for the shuffle in {@link #addSubmittedEventsToEventLog()}.
     */
    private NodeRanks ranks = NodeRanks.NONE;

    /**
     * Whether {@link #setUp(Collection)} has been called.
     */
    private boolean isSetUp = false;

    /**
     * The connection data passed to {@link #setConnections(Map)} before the network was set up, in the order it was
     * passed. It is applied by {@link #setUp(Collection)} in the same order, so that the outcome is the same as
     * if every node had been present all along.
     */
    private final List<Map<ConnectionKey, ConnectionState>> connectionsBeforeSetUp = new ArrayList<>();

    /**
     * The order {@link #newlySubmittedEvents} is drained in on the current tick. A shuffled copy of the node ids in node
     * id order, reused across ticks so that producing it does not allocate.
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
     * <p>Drained by {@link #scheduleEventWindows(long)}. Until then it also serves as the record of
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
     * <p>Each connection's queue is ordered by arrival time, so delivery does not depend on the clamp in
     * {@link ArrivalTimes}. The clamp just keeps each kind of route on a connection from being reordered by jitter.
     */
    private final InFlightEvents inFlightEvents;

    /**
     * Event windows that have been transmitted onto the network but not yet delivered.
     */
    private final InFlightEventWindows inFlightEventWindows;

    /**
     * Everything in flight, in the order it is delivered within a tick: event windows first, then events. Only delivery
     * is driven from this list; what is put in flight comes from different places, see
     * {@link #scheduleForDelivery(long)}.
     */
    private final List<InFlightConnections<?>> inFlightInDeliveryOrder;

    /**
     * The gossip "component" for each node in the network, indexed by rank.
     */
    private final List<EventReceiver> eventReceivers = new ArrayList<>();

    /**
     * The state of every connection and link. Replaced by {@link #setUp(Collection)}, until which the network has no
     * nodes.
     */
    private LinkStates links;

    /**
     * Picks the arrival times of everything put onto a connection. Replaced by {@link #setUp(Collection)}, until which
     * the network has no nodes.
     */
    private ArrivalTimes arrivalTimes;

    /**
     * Handles each link that comes up, see {@link #linkCameUp(int, int)}. Kept in a field so that handing it to
     * {@link LinkStates} does not allocate.
     */
    private final LinkStates.LinkUpListener linkUpListener = this::linkCameUp;

    /**
     * Constructor using the default production broadcast and sync settings.
     *
     * @param random the random number generator to use for simulating network delays
     * @param start  the instant the simulation starts at
     */
    public SimulatedNetworkConnectivity(@NonNull final Random random, @NonNull final Instant start) {
        this(
                random,
                start,
                new TestConfigBuilder()
                        .withConfigDataType(BroadcastConfig.class)
                        .getOrCreateConfig()
                        .getConfigData(BroadcastConfig.class),
                new TestConfigBuilder()
                        .withConfigDataType(SyncConfig.class)
                        .getOrCreateConfig()
                        .getConfigData(SyncConfig.class));
    }

    /**
     * Constructor.
     *
     * @param random          the random number generator to use for simulating network delays
     * @param start           the instant the simulation starts at
     * @param broadcastConfig the production broadcast settings the delivery delays are derived from
     * @param syncConfig      the production sync settings the delivery delays are derived from
     */
    public SimulatedNetworkConnectivity(
            @NonNull final Random random,
            @NonNull final Instant start,
            @NonNull final BroadcastConfig broadcastConfig,
            @NonNull final SyncConfig syncConfig) {
        this.random = requireNonNull(random);
        this.start = requireNonNull(start);
        this.broadcastConfig = requireNonNull(broadcastConfig);
        this.syncConfig = requireNonNull(syncConfig);
        links = new LinkStates(0, random, broadcastConfig);
        arrivalTimes = new ArrivalTimes(start, random, links, 0, broadcastConfig, syncConfig);
        inFlightEvents = new InFlightEvents(this::isConnected, nodeEventWindows::get, start);
        inFlightEventWindows = new InFlightEventWindows(this::isConnected, start);
        inFlightInDeliveryOrder = List.of(inFlightEventWindows, inFlightEvents);
        eventLog = new CursoredLog<>(
                ConsensusConstants.ROUND_FIRST,
                INITIAL_SEQUENCE_NUMBER_CAPACITY,
                INITIAL_EVENT_LOG_CAPACITY,
                PlatformEvent::getBirthRound);
    }

    /**
     * Sets up the whole network at once, with one node for every event receiver. Connection data passed to
     * {@link #setConnections(Map)} before this call is applied now, in the order it was passed.
     *
     * <p>The nodes are visited in node id order throughout the simulation, whatever order the event receivers are given
     * in. Visiting them in a fixed order is what keeps the simulation deterministic.
     *
     * <p>Setting up again with the same nodes, as happens when a network is restarted, keeps the current state.
     * Changing the nodes of a network that is already set up is not supported.
     *
     * @param eventReceivers the event receiver of every node in the network, in any order
     * @throws IllegalArgumentException if two event receivers belong to the same node
     * @throws IllegalStateException    if the network has already been set up with different nodes
     */
    public void setUp(@NonNull final Collection<? extends EventReceiver> eventReceivers) {
        final Map<NodeId, EventReceiver> receiversById = new HashMap<>();
        for (final EventReceiver eventReceiver : eventReceivers) {
            if (receiversById.put(eventReceiver.getNodeId(), eventReceiver) != null) {
                throw new IllegalArgumentException(
                        "There is more than one event receiver for " + eventReceiver.getNodeId());
            }
        }
        final NodeRanks newRanks = new NodeRanks(receiversById.keySet());
        if (isSetUp) {
            if (!ranks.nodeIds().equals(newRanks.nodeIds())) {
                throw new IllegalStateException("Changing the nodes of the network is not supported: " + ranks.nodeIds()
                        + " -> " + newRanks.nodeIds());
            }
            return;
        }

        ranks = newRanks;
        final int nodeCount = ranks.size();
        for (final InFlightConnections<?> inFlight : inFlightInDeliveryOrder) {
            inFlight.setUp(nodeCount);
        }
        for (final NodeId nodeId : ranks.nodeIds()) {
            this.eventReceivers.add(receiversById.get(nodeId));
            nodeCursors.add(eventLog.newCursor());
            nodeEventWindows.put(nodeId, EventWindow.getGenesisEventWindow());
            newlySubmittedEvents.put(nodeId, new ArrayList<>());
        }
        links = new LinkStates(nodeCount, random, broadcastConfig);
        arrivalTimes = new ArrivalTimes(start, random, links, nodeCount, broadcastConfig, syncConfig);
        isSetUp = true;

        for (final Map<ConnectionKey, ConnectionState> pendingConnections : connectionsBeforeSetUp) {
            setConnections(pendingConnections);
        }
        connectionsBeforeSetUp.clear();
    }

    /**
     * Set the connection data for this simulated network. If the network has not been set up yet, the data is held and
     * applied by {@link #setUp(Collection)}.
     *
     * <p>A link is a pair of nodes, and is up only if both directions are connected. A link that has come up is not
     * usable until a reconnect delay has passed, and its two ends are each sent the other's latest event window again,
     * as they would be when a real connection is established. See
     * {@link LinkStates#update(Map, NodeRanks, long, LinkStates.LinkUpListener)} for how links that have gone down are
     * treated, and why the first connection data has no delay.
     *
     * @param newConnections the connection data
     * @throws IllegalArgumentException if the data contains a connection of a node that is not part of the network
     */
    public void setConnections(@NonNull final Map<ConnectionKey, ConnectionState> newConnections) {
        if (!isSetUp) {
            connectionsBeforeSetUp.add(Map.copyOf(newConnections));
            return;
        }
        // links come up in rank order, so that the random numbers drawn for them are drawn in a reproducible order
        links.update(newConnections, ranks, currentTime, linkUpListener);
    }

    /**
     * Starts the reconnect delay of a link that has just come up and sends each of its ends the other's latest event
     * window.
     *
     * @param first  the rank of the node of the link that comes first in node id order
     * @param second the rank of the other node of the link
     */
    private void linkCameUp(final int first, final int second) {
        links.startReconnectDelay(first, second, currentTime);
        resendEventWindow(first, second);
        resendEventWindow(second, first);
    }

    /**
     * Puts the latest event window of a node in flight to a peer. Event windows are otherwise only sent when they
     * change, so a peer that has only just become reachable would not hear the window until the next change.
     *
     * @param sender   the rank of the node whose latest event window is sent
     * @param receiver the rank of the node it is sent to
     */
    private void resendEventWindow(final int sender, final int receiver) {
        final NodeId senderId = ranks.idOf(sender);
        final EventWindow eventWindow = nodeEventWindows.get(senderId);
        final Instant arrivalTime = arrivalTimes.next(sender, receiver, MessageRoute.BROADCAST, currentTime);
        if (arrivalTime != null) {
            inFlightEventWindows.add(sender, receiver, new EventWindowInTransit(eventWindow, senderId, arrivalTime));
        }
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
        submissionOrder.addAll(ranks.nodeIds());
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
     * {@link #scheduleEventWindows(long)}.
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
        currentTime = InFlightQueue.nanosSince(start, now);
        // The oldest event window is applied first, so that the window the deduplicator discards expired events by
        // matches the range of birth rounds the log still accepts when the submissions below are appended.
        applyOldestEventWindow();
        addSubmittedEventsToEventLog();
        // Events are delivered before new ones are scheduled, so that an event always spends at least one tick in
        // flight. Scheduling first would let a connection configured with no latency deliver an event in the same
        // tick it was sent, with no simulated time passing in between.
        deliverArrived(now);
        scheduleForDelivery(currentTime);
    }

    /**
     * Deliver what has arrived and can be delivered right now. Every node is given the newest event window each of its
     * peers has landed at it, and only then is every node given the events that have arrived at it.
     *
     * @param now the current time
     */
    private void deliverArrived(@NonNull final Instant now) {
        // The order the nodes are delivered to does not need to be randomized. The nodes are not running on any thread
        // when this method is called, and so the order in which nodes are provided events makes no difference.
        for (final InFlightConnections<?> inFlight : inFlightInDeliveryOrder) {
            for (int receiver = 0; receiver < eventReceivers.size(); receiver++) {
                inFlight.deliverArrived(now, receiver, eventReceivers.get(receiver));
            }
        }
    }

    /**
     * Reports whether things can currently be delivered from a sender to a receiver, see
     * {@link LinkStates#isConnected(int, int, long)}.
     *
     * @param sender   the rank of the node at the sending end of the connection
     * @param receiver the rank of the node at the receiving end of the connection
     * @return {@code true} if things can travel from the sender to the receiver
     */
    private boolean isConnected(final int sender, final int receiver) {
        return links.isConnected(sender, receiver, currentTime);
    }

    /**
     * Put onto the network everything that is waiting to be sent: the event windows that nodes have reported since the
     * previous tick, and the events that each node's cursor has not handed out yet.
     *
     * <p>The event windows are scheduled first, sender by sender, and then the events, receiver by receiver. This is
     * the order {@link #deliverArrived(Instant)} follows too, which delivers the event windows to every receiver
     * before any events. The order is not incidental: everything scheduled draws an arrival time from
     * {@link #random}, and a window and an event on the same connection share the clamp in {@link ArrivalTimes}, so
     * which goes first decides the arrival times of both, and the same seed only reproduces a run if it is always the
     * same.
     *
     * @param now the current time, in nanoseconds since the start
     */
    private void scheduleForDelivery(final long now) {
        if (!links.hasConnections()) {
            // No connections have been set, so there is nowhere to send anything.
            return;
        }

        scheduleEventWindows(now);
        for (int receiver = 0; receiver < ranks.size(); receiver++) {
            scheduleEventsTo(receiver, now);
        }
    }

    /**
     * Schedule the event windows reported since the previous tick for delivery, sender by sender, to every node the
     * sender has a connection to. A node is not sent its own window, and a node that has not reported since the last
     * tick has nothing to send, because its peers already hold the window it would send.
     *
     * <p>This drains {@link #newlyReportedEventWindows}.
     *
     * @param now the current time, in nanoseconds since the start
     */
    private void scheduleEventWindows(final long now) {
        final int nodeCount = ranks.size();
        for (int sender = 0; sender < nodeCount; sender++) {
            final NodeId senderId = ranks.idOf(sender);
            final EventWindow eventWindow = newlyReportedEventWindows.remove(senderId);
            if (eventWindow == null) {
                continue;
            }

            for (int receiver = 0; receiver < nodeCount; receiver++) {
                if (sender == receiver) {
                    continue;
                }

                // As with events, whether the connection is up is decided at delivery time. A window held through a
                // partition is superseded by the ones scheduled behind it, so when the connection returns the
                // receiver is given the sender's current window rather than a stale one.
                final Instant arrivalTime = arrivalTimes.next(sender, receiver, MessageRoute.BROADCAST, now);
                if (arrivalTime != null) {
                    inFlightEventWindows.add(
                            sender, receiver, new EventWindowInTransit(eventWindow, senderId, arrivalTime));
                }
            }
        }
    }

    /**
     * Walk a node's cursor over {@link #eventLog} and schedule every event it has not been sent yet for delivery. A
     * node is not sent its own events, nor events that are already ancient for it.
     *
     * <p>When each event arrives is decided by {@link ArrivalTimes#next(int, int, MessageRoute, long)}.
     *
     * @param receiver the rank of the node the events are traveling to
     * @param now      the current time, in nanoseconds since the start
     */
    private void scheduleEventsTo(final int receiver, final long now) {
        final Cursor<PlatformEvent> cursor = nodeCursors.get(receiver);
        final EventWindow receiverEventWindow = nodeEventWindows.get(ranks.idOf(receiver));

        while (cursor.hasNext()) {
            final PlatformEvent event = cursor.next();
            final NodeId senderId = event.getSenderId();
            assert senderId != null;
            final int sender = ranks.rankOf(senderId);

            // Don't send a node's own events back to it
            if (receiver == sender) {
                continue;
            }

            // The receiver would discard this event on arrival, so there is no point in transmitting it
            if (receiverEventWindow.isAncient(event)) {
                continue;
            }

            // The sender and receiver may be known to each other, in which case the event is enqueued for delivery.
            // Whether the connection is up is decided at delivery time rather than here: the cursor is consumed
            // either way, so an event dropped now would never be offered to this receiver again, and a partition
            // would permanently deprive it of every event created while the partition was in place.
            final boolean broadcast =
                    senderId.equals(event.getCreatorId()) && links.isBroadcastMode(sender, receiver, now);
            final MessageRoute route = broadcast ? MessageRoute.BROADCAST : MessageRoute.SYNC;
            final Instant arrivalTime = arrivalTimes.next(sender, receiver, route, now);
            if (arrivalTime != null) {
                // create a copy so that nodes don't modify each other's events
                final PlatformEvent eventToDeliver = event.copyGossipedData();
                eventToDeliver.setSenderId(senderId);
                eventToDeliver.setTimeReceived(arrivalTime);
                inFlightEvents.add(sender, receiver, new EventInTransit(eventToDeliver, senderId, arrivalTime));
            }
        }
    }

    /**
     * Reset the cursor for a node to the beginning of the event log. This is useful for testing scenarios where a node
     * is restarted and needs to reprocess all events from the beginning.
     *
     * <p>A restart counts as all of the node's links coming up: each of them has to wait out a reconnect delay, and
     * each of its peers is sent its latest event window again, since the windows that were waiting for the node are
     * discarded here.
     *
     * @param nodeId the id of the node whose cursor should be reset
     */
    public void resetCursor(@NonNull final NodeId nodeId) {
        final int node = ranks.rankOf(nodeId);
        nodeCursors.get(node).seekToFirst();
        for (final InFlightConnections<?> inFlight : inFlightInDeliveryOrder) {
            inFlight.clearIncoming(node);
        }
        links.restartLinksOf(node, linkUpListener);
    }
}
