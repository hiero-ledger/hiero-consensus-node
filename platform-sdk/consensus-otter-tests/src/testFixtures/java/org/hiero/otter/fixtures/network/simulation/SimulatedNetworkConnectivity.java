// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import static java.util.Objects.requireNonNull;

import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
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
     * How many round trips of a link pass between it coming up and anything being delivered over it. This stands in
     * for the TCP and TLS handshakes, protocol negotiation and first sync.
     */
    private static final double RECONNECT_ROUND_TRIPS = 5.0;

    /**
     * The reconnect delay is randomly varied by up to this fraction in either direction, so that links which come up
     * together do not all become usable at the same moment.
     */
    private static final double RECONNECT_JITTER = 0.2;

    /**
     * The production broadcast settings the delivery delays are derived from. Only the defaults are used.
     */
    private static final BroadcastConfig BROADCAST_CONFIG = new TestConfigBuilder()
            .withConfigDataType(BroadcastConfig.class)
            .getOrCreateConfig()
            .getConfigData(BroadcastConfig.class);

    /**
     * The production sync settings the delivery delays are derived from. Only the defaults are used.
     */
    private static final SyncConfig SYNC_CONFIG = new TestConfigBuilder()
            .withConfigDataType(SyncConfig.class)
            .getOrCreateConfig()
            .getConfigData(SyncConfig.class);

    /**
     * The random number generator to use for simulating network delays.
     */
    private final Random random;

    /**
     * The time of the most recent {@link #tick(Instant)}. This is the time that changes to the connections are taken
     * to happen at, and the time that delivery is judged against. Before the first tick it is the start of the
     * simulation, so that anything sent then gets an arrival time the in-flight queues can measure from the start.
     */
    private Instant currentTime;

    /**
     * For every link that came up recently, the time before which nothing is delivered over it in either direction.
     * A link with no entry has no delay pending.
     */
    private final Map<LinkKey, Instant> reconnectReadyAt = new HashMap<>();

    /**
     * For every link that is, or recently was, out of broadcast mode, the time from which it is in broadcast mode
     * again. {@link Instant#MAX} while the round trip of the link is above the threshold. A link with no entry is in
     * broadcast mode.
     */
    private final Map<LinkKey, Instant> broadcastEnabledAt = new HashMap<>();

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
     * Every node in the network, in node id order. Filled once by {@link #setUp(Collection)} and never reordered, so
     * that it can serve as the canonical starting point for the shuffle in {@link #addSubmittedEventsToEventLog()}.
     */
    private final List<NodeId> sortedNodeIds = new ArrayList<>();

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
     * <p>Drained by {@link #scheduleEventWindows(Instant)}. Until then it also serves as the record of
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
     * <p>Each connection's queue is ordered by arrival time, so delivery does not depend on {@link #arrivalTimeClamp}.
     * The clamp just keeps each kind of route on a connection from being reordered by jitter.
     */
    private final InFlightEvents inFlightEvents;

    /**
     * Event windows that have been transmitted onto the network but not yet delivered.
     */
    private final InFlightEventWindows inFlightEventWindows;

    /**
     * Everything in flight, in the order it is delivered within a tick: event windows first, then events. Only delivery
     * is driven from this list; what is put in flight comes from different places, see
     * {@link #scheduleForDelivery(Instant)}.
     */
    private final List<InFlightConnections<?>> inFlightInDeliveryOrder;

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
     * Keeps the arrival times of each kind of route on each connection in order, even when network jitter is applied.
     */
    private final ArrivalTimeClamp arrivalTimeClamp = new ArrivalTimeClamp();

    /**
     * Constructor.
     *
     * @param random the random number generator to use for simulating network delays
     * @param start  the instant the simulation starts at
     */
    public SimulatedNetworkConnectivity(@NonNull final Random random, @NonNull final Instant start) {
        this.random = requireNonNull(random);
        this.currentTime = requireNonNull(start);
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
        final List<NodeId> nodeIds = receiversById.keySet().stream().sorted().toList();
        if (isSetUp) {
            if (!sortedNodeIds.equals(nodeIds)) {
                throw new IllegalStateException(
                        "Changing the nodes of the network is not supported: " + sortedNodeIds + " -> " + nodeIds);
            }
            return;
        }

        for (final InFlightConnections<?> inFlight : inFlightInDeliveryOrder) {
            inFlight.setUp(nodeIds);
        }
        for (final NodeId nodeId : nodeIds) {
            this.eventReceivers.put(nodeId, receiversById.get(nodeId));
            nodeCursors.put(nodeId, eventLog.newCursor());
            nodeEventWindows.put(nodeId, EventWindow.getGenesisEventWindow());
            newlySubmittedEvents.put(nodeId, new ArrayList<>());
        }
        sortedNodeIds.addAll(nodeIds);
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
     * <p>The new state is compared with the old one link by link, where a link is a pair of nodes and is up only if
     * both directions are connected. A link that has come up is not usable until a reconnect delay has passed, and its
     * two ends are each sent the other's latest event window again, as they would be when a real connection is
     * established. A link that has gone down loses any delay it was waiting out, so that nothing gets through it before
     * a full delay has passed after it next comes up.
     *
     * <p>The first call is taken to describe links that are already established, and so has no delay.
     *
     * @param newConnections the connection data
     */
    public void setConnections(@NonNull final Map<ConnectionKey, ConnectionState> newConnections) {
        if (!isSetUp) {
            connectionsBeforeSetUp.add(Map.copyOf(newConnections));
            return;
        }

        final boolean initial = connections.isEmpty();
        final Map<ConnectionKey, ConnectionState> oldConnections = new HashMap<>(connections);
        connections.clear();
        connections.putAll(newConnections);
        updateBroadcastModes();
        if (initial) {
            reconnectReadyAt.clear();
            return;
        }

        // sorted, so that the random numbers drawn below are drawn in a reproducible order
        final TreeSet<LinkKey> links = new TreeSet<>();
        for (final ConnectionKey key : oldConnections.keySet()) {
            links.add(LinkKey.of(key));
        }
        for (final ConnectionKey key : newConnections.keySet()) {
            links.add(LinkKey.of(key));
        }

        for (final LinkKey link : links) {
            final boolean wasUp = isLinkUp(oldConnections, link);
            if (!isLinkUp(connections, link)) {
                reconnectReadyAt.remove(link);
            } else if (!wasUp) {
                linkCameUp(link);
            }
        }
    }

    /**
     * Updates the broadcast mode of every link from its current round trip. A link leaves broadcast mode when its round
     * trip exceeds the threshold, and returns to it a fixed time after the round trip falls below the threshold again.
     */
    private void updateBroadcastModes() {
        final TreeSet<LinkKey> links = new TreeSet<>();
        for (final ConnectionKey key : connections.keySet()) {
            links.add(LinkKey.of(key));
        }
        for (final LinkKey link : links) {
            final boolean tooSlow =
                    roundTrip(link.first(), link.second()).compareTo(BROADCAST_CONFIG.disablePingThreshold()) > 0;
            final Instant enabledAt = broadcastEnabledAt.get(link);
            if (tooSlow) {
                broadcastEnabledAt.put(link, Instant.MAX);
            } else if (Instant.MAX.equals(enabledAt)) {
                broadcastEnabledAt.put(link, currentTime.plus(BROADCAST_CONFIG.pauseOnLag()));
            }
        }
    }

    /**
     * Reports whether a link is in broadcast mode at the current time.
     */
    private boolean isBroadcastMode(@NonNull final NodeId a, @NonNull final NodeId b) {
        final Instant enabledAt = broadcastEnabledAt.get(new LinkKey(a, b));
        return enabledAt == null || !currentTime.isBefore(enabledAt);
    }

    /**
     * The sum of the latencies of both directions of a link. A missing direction counts as the other one.
     */
    @NonNull
    private Duration roundTrip(@NonNull final NodeId a, @NonNull final NodeId b) {
        final ConnectionState forward = connections.get(new ConnectionKey(a, b));
        final ConnectionState backward = connections.get(new ConnectionKey(b, a));
        final Duration forwardLatency = forward != null ? forward.latency() : backward.latency();
        final Duration backwardLatency = backward != null ? backward.latency() : forwardLatency;
        return forwardLatency.plus(backwardLatency);
    }

    /**
     * Reports whether both directions of a link are connected in the given state.
     *
     * @param state the connection state to look in
     * @param link  the key of the link
     * @return {@code true} if both directions are connected
     */
    private static boolean isLinkUp(
            @NonNull final Map<ConnectionKey, ConnectionState> state, @NonNull final LinkKey link) {
        final ConnectionState forward = state.get(link.firstToSecond());
        final ConnectionState backward = state.get(link.secondToFirst());
        return forward != null && forward.connected() && backward != null && backward.connected();
    }

    /**
     * Starts the reconnect delay of a link that has just come up and sends each of its ends the other's latest event
     * window. The delay is a fixed number of round trips of the link, varied by a little jitter.
     *
     * @param link the key of the link
     */
    private void linkCameUp(@NonNull final LinkKey link) {
        final ConnectionState forward = connections.get(link.firstToSecond());
        final ConnectionState backward = connections.get(link.secondToFirst());
        final Duration roundTrip = forward.latency().plus(backward.latency());
        final double jitter = RECONNECT_JITTER * (2 * random.nextDouble() - 1);
        final Duration delay = Duration.ofNanos((long) (roundTrip.toNanos() * RECONNECT_ROUND_TRIPS * (1 + jitter)));
        reconnectReadyAt.put(link, currentTime.plus(delay));

        resendEventWindow(link.first(), link.second());
        resendEventWindow(link.second(), link.first());
    }

    /**
     * Puts the latest event window of a node in flight to a peer. Event windows are otherwise only sent when they
     * change, so a peer that has only just become reachable would not hear the window until the next change.
     *
     * @param sender   the node whose latest event window is sent
     * @param receiver the node it is sent to
     */
    private void resendEventWindow(@NonNull final NodeId sender, @NonNull final NodeId receiver) {
        final EventWindow eventWindow = nodeEventWindows.get(sender);
        if (eventWindow == null || !eventReceivers.containsKey(receiver)) {
            // a node that is not part of the network has no window to send and nowhere to receive one
            return;
        }
        final Instant arrivalTime = nextArrivalTime(sender, receiver, MessageRoute.BROADCAST, currentTime);
        if (arrivalTime != null) {
            inFlightEventWindows.add(receiver, new EventWindowInTransit(eventWindow, sender, arrivalTime));
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
     * {@link #scheduleEventWindows(Instant)}.
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
        currentTime = now;
        // The oldest event window is applied first, so that the window the deduplicator discards expired events by
        // matches the range of birth rounds the log still accepts when the submissions below are appended.
        applyOldestEventWindow();
        addSubmittedEventsToEventLog();
        // Events are delivered before new ones are scheduled, so that an event always spends at least one tick in
        // flight. Scheduling first would let a connection configured with no latency deliver an event in the same
        // tick it was sent, with no simulated time passing in between.
        deliverArrived(now);
        scheduleForDelivery(now);
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
            for (final NodeId receiverId : sortedNodeIds) {
                inFlight.deliverArrived(now, eventReceivers.get(receiverId));
            }
        }
    }

    /**
     * Reports whether things can currently be delivered from a sender to a receiver.
     *
     * <p>This requires the connection to be up in both directions, not only from the sender to the receiver. In
     * production the two nodes share one TCP connection, and the side that stops receiving closes it. It also requires
     * the reconnect delay, if the link came up recently, to have passed.
     *
     * @param sender   the node at the sending end of the connection
     * @param receiver the node at the receiving end of the connection
     * @return {@code true} if events can travel from the sender to the receiver
     */
    private boolean isConnected(@NonNull final NodeId sender, @NonNull final NodeId receiver) {
        final LinkKey link = new LinkKey(sender, receiver);
        if (!isLinkUp(connections, link)) {
            return false;
        }
        final Instant readyAt = reconnectReadyAt.get(link);
        return readyAt == null || !currentTime.isBefore(readyAt);
    }

    /**
     * Put onto the network everything that is waiting to be sent: the event windows that nodes have reported since the
     * previous tick, and the events that each node's cursor has not handed out yet.
     *
     * <p>The event windows are scheduled first, sender by sender, and then the events, receiver by receiver. This is
     * the order {@link #deliverArrived(Instant)} follows too, which delivers the event windows to every receiver
     * before any events. The order is not incidental: everything scheduled draws an arrival time from
     * {@link #random}, and a window and an event on the same connection share the clamp in
     * {@link #arrivalTimeClamp}, so which goes first decides the arrival times of both, and the same seed only
     * reproduces a run if it is always the same.
     *
     * @param now the current time
     */
    private void scheduleForDelivery(@NonNull final Instant now) {
        if (connections.isEmpty()) {
            // No connections have been set, so there is nowhere to send anything.
            return;
        }

        scheduleEventWindows(now);
        for (final NodeId receiver : sortedNodeIds) {
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
     * @param now the current time
     */
    private void scheduleEventWindows(@NonNull final Instant now) {
        for (final NodeId sender : sortedNodeIds) {
            final EventWindow eventWindow = newlyReportedEventWindows.remove(sender);
            if (eventWindow == null) {
                continue;
            }

            for (final NodeId receiver : sortedNodeIds) {
                if (sender.equals(receiver)) {
                    continue;
                }

                // As with events, whether the connection is up is decided at delivery time. A window held through a
                // partition is superseded by the ones scheduled behind it, so when the connection returns the
                // receiver is given the sender's current window rather than a stale one.
                final Instant arrivalTime = nextArrivalTime(sender, receiver, MessageRoute.BROADCAST, now);
                if (arrivalTime != null) {
                    inFlightEventWindows.add(receiver, new EventWindowInTransit(eventWindow, sender, arrivalTime));
                }
            }
        }
    }

    /**
     * Walk a node's cursor over {@link #eventLog} and schedule every event it has not been sent yet for delivery. A
     * node is not sent its own events, nor events that are already ancient for it.
     *
     * <p>When each event arrives is decided by
     * {@link #nextArrivalTime(NodeId, NodeId, MessageRoute, Instant)}.
     *
     * @param receiver the node the events are traveling to
     * @param now      the current time
     */
    private void scheduleEventsTo(@NonNull final NodeId receiver, @NonNull final Instant now) {
        final Cursor<PlatformEvent> cursor = nodeCursors.get(receiver);
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

            // The sender and receiver may be known to each other, in which case the event is enqueued for delivery.
            // Whether the connection is up is decided at delivery time rather than here: the cursor is consumed
            // either way, so an event dropped now would never be offered to this receiver again, and a partition
            // would permanently deprive it of every event created while the partition was in place.
            final boolean broadcast = sender.equals(event.getCreatorId()) && isBroadcastMode(sender, receiver);
            final Instant arrivalTime = broadcast
                    ? nextArrivalTime(sender, receiver, MessageRoute.BROADCAST, now)
                    : nextArrivalTime(sender, receiver, MessageRoute.SYNC, now);
            if (arrivalTime != null) {
                // create a copy so that nodes don't modify each other's events
                final PlatformEvent eventToDeliver = event.copyGossipedData();
                eventToDeliver.setSenderId(sender);
                eventToDeliver.setTimeReceived(arrivalTime);
                inFlightEvents.add(receiver, new EventInTransit(eventToDeliver, sender, arrivalTime));
            }
        }
    }

    /**
     * Picks the time something scheduled onto a connection now will arrive at the other end.
     *
     * <p>The time is the current time plus the connection's latency, offset by a jitter value drawn from a truncated
     * Gaussian distribution. Jitter can pull an arrival time earlier than one already scheduled on the same connection,
     * so the result is clamped to be no earlier than the previous one of the same kind on the same connection. That
     * clamp keeps each kind of route in order.
     *
     * <p>Picking draws from {@link #random}, so the order callers call this in is part of what a seed reproduces. No
     * draw is made when the sender and receiver are not known to each other.
     *
     * @param sender   the node at the sending end of the connection
     * @param receiver the node at the receiving end of the connection
     * @param kind     the route over which message is being scheduled
     * @param now      the current time
     * @return the time of arrival at the receiving end, or {@code null} if the two nodes have no connection
     */
    @Nullable
    private Instant nextArrivalTime(
            @NonNull final NodeId sender,
            @NonNull final NodeId receiver,
            @NonNull final MessageRoute kind,
            @NonNull final Instant now) {
        final ConnectionKey connectionKey = new ConnectionKey(sender, receiver);
        final ConnectionState connectionState = connections.get(connectionKey);
        if (connectionState == null) {
            return null;
        }

        // Simulate network latency and jitter using truncated Gaussian distribution
        final double sigma = connectionState.latency().toNanos() * connectionState.jitter().value / 100.0;
        final double jitter = Math.clamp(random.nextGaussian() * sigma, -3 * sigma, 3 * sigma);
        Instant arrivalTime = now.plus(connectionState.latency()).plusNanos((long) jitter);
        if (kind == MessageRoute.SYNC) {
            arrivalTime = arrivalTime.plus(syncDelay(sender, receiver));
        }

        // Ensure arrival time is always incremental within a kind of route
        return arrivalTimeClamp.clamp(connectionKey, kind, arrivalTime);
    }

    /**
     * The time a copy sent by sync waits on top of the link latency: the hold-back applied by the sender, and the
     * average wait for the next sync to start. Syncs of a pair run back to back, each taking about two round trips plus
     * a pause, so the average wait is one round trip plus half the pause. Without broadcast there is no hold-back and
     * the pause is shorter.
     *
     * <p>The hold-back of 3 seconds that production applies to events the forwarder does not build on is not simulated.
     */
    @NonNull
    private Duration syncDelay(@NonNull final NodeId sender, @NonNull final NodeId receiver) {
        final boolean broadcastMode = isBroadcastMode(sender, receiver);
        final Duration holdBack = broadcastMode ? SYNC_CONFIG.ancestorFilterThreshold() : Duration.ZERO;
        final Duration pause =
                broadcastMode ? BROADCAST_CONFIG.rpcSleepAfterSyncWhileBroadcasting() : SYNC_CONFIG.rpcSleepAfterSync();
        return holdBack.plus(roundTrip(sender, receiver)).plus(pause.dividedBy(2));
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
        nodeCursors.get(nodeId).seekToFirst();
        for (final InFlightConnections<?> inFlight : inFlightInDeliveryOrder) {
            inFlight.clearIncoming(nodeId);
        }
        for (final NodeId peer : sortedNodeIds) {
            if (peer.equals(nodeId)) {
                continue;
            }
            final LinkKey link = new LinkKey(nodeId, peer);
            if (isLinkUp(connections, link)) {
                linkCameUp(link);
            }
        }
    }
}
