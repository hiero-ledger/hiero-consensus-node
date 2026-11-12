// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import static java.util.Objects.requireNonNull;

import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.hiero.consensus.gossip.config.BroadcastConfig;
import org.hiero.consensus.gossip.config.SyncConfig;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.otter.fixtures.internal.network.ConnectionKey;
import org.hiero.otter.fixtures.network.Topology.ConnectionState;
import org.hiero.otter.fixtures.turtle.gossip.SimulatedGossip;
import org.hiero.otter.fixtures.util.CursoredLog.Cursor;

/**
 * Connects {@link SimulatedGossip} peers in a simulated network.
 *
 * <p>This is not an implementation of the production gossip protocol: no syncs are run and no messages are exchanged
 * between peers. Instead, it reproduces when production gossip would deliver each event. A node's own events are
 * broadcast while the link is in broadcast mode, and everything else arrives with the delay of a sync, see
 * {@link ArrivalTimes}. Only copies that can arrive first are sent, so receivers see each event at the same time as if
 * every copy were sent, without most of the duplicates, see {@link EventRouter}.
 *
 * <p>This class drives the simulation tick by tick and holds its parts together:
 * <ul>
 *     <li>{@link LinkStates} keeps the state of the connections, and {@link ArrivalTimes} picks the arrival time of
 *     everything put onto a connection;</li>
 *     <li>{@link PendingSubmissions} and {@link NodeEventWindows} hold what the nodes submit and report while they
 *     run, until the next tick;</li>
 *     <li>{@link TrackedEvents} keeps the events that have been submitted, and {@link EventRouter} decides which copies
 *     of them are sent;</li>
 *     <li>{@link InFlightEvents} and {@link InFlightEventWindows} hold what is in flight until it is delivered.</li>
 * </ul>
 *
 * <p>All of them identify nodes by their rank, which is the index of the node in the roster, and this class looks up
 * the rank of a node only when the network is set up and when an event enters the network. Times are kept internally as nanoseconds since the
 * start of the simulation.
 *
 * <p>The nodes of the network are fixed when an instance is created and never change. An instance only runs the
 * network: it is created when the network starts and discarded when the network is shut down. A network with other
 * nodes, for example after a roster change, is run by a new instance.
 */
public class SimulatedNetworkConnectivity implements GossipNetwork, NetworkView, LinkStates.LinkListener {

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
     * Events submitted by each node since the previous tick, held until they can be scheduled for delivery.
     */
    private final PendingSubmissions pendingSubmissions;

    /**
     * The ranks of the nodes that have restarted since the previous tick, whose events are sent again by
     * {@link #replayEventLog(long)}. Nodes restart while they are running concurrently.
     */
    private final Set<Integer> restartedNodes = ConcurrentHashMap.newKeySet();

    /**
     * The roster of the network. A node's rank is its index in the roster. Fixed by the constructor.
     */
    private final RosterWrapper roster;

    /**
     * The event windows the nodes report.
     */
    private final NodeEventWindows eventWindows;

    /**
     * The events that have been submitted and are not expired for every node yet.
     */
    private final TrackedEvents trackedEvents;

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
    private final List<EventReceiver> eventReceivers;

    /**
     * The state of every connection and link.
     */
    private final LinkStates links;

    /**
     * Picks the arrival times of everything put onto a connection.
     */
    private final ArrivalTimes arrivalTimes;

    /**
     * Decides which copies of the submitted events are sent.
     */
    private final EventRouter router;

    /**
     * Follow the gossip for add-ons, such as the estimate of duplicate events, in the order they were added.
     */
    private final List<NetworkObserver> observers = new ArrayList<>();

    /**
     * Constructor using the default production broadcast and sync settings, in which a node receives copies of its own
     * events, see {@link #SimulatedNetworkConnectivity(Random, Instant, RosterWrapper, Collection, boolean)}.
     *
     * @param random         the random number generator to use for simulating network delays
     * @param start          the instant the simulation starts at
     * @param roster         the roster of the network
     * @param eventReceivers the event receiver of every node in the roster, in any order
     * @throws IllegalArgumentException if the event receivers do not belong to exactly the nodes of the roster
     */
    public SimulatedNetworkConnectivity(
            @NonNull final Random random,
            @NonNull final Instant start,
            @NonNull final RosterWrapper roster,
            @NonNull final Collection<? extends EventReceiver> eventReceivers) {
        this(random, start, roster, eventReceivers, true);
    }

    /**
     * Constructor using the default production broadcast and sync settings.
     *
     * @param random         the random number generator to use for simulating network delays
     * @param start          the instant the simulation starts at
     * @param roster         the roster of the network
     * @param eventReceivers the event receiver of every node in the roster, in any order
     * @param echoes         whether a node receives copies of its own events that other nodes forward. With echoes, a
     *                       node is sent a forwarded copy of its own event like it is sent any other, which is subject
     *                       to the rule that only a copy that arrives before every other copy on its way to the node is
     *                       sent. Without them it is sent none.
     * @throws IllegalArgumentException if the event receivers do not belong to exactly the nodes of the roster
     */
    public SimulatedNetworkConnectivity(
            @NonNull final Random random,
            @NonNull final Instant start,
            @NonNull final RosterWrapper roster,
            @NonNull final Collection<? extends EventReceiver> eventReceivers,
            final boolean echoes) {
        this(
                random,
                start,
                roster,
                eventReceivers,
                echoes,
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
     * Constructor. Sets up the whole network at once, with one node for every entry of the roster. The nodes cannot be
     * changed afterwards.
     *
     * <p>The nodes are visited in roster order throughout the simulation, whatever order the event receivers are given
     * in. Visiting them in a fixed order is what keeps the simulation deterministic.
     *
     * <p>The network has no connections until {@link #setConnections(Map)} is called for the first time.
     *
     * @param random          the random number generator to use for simulating network delays
     * @param start           the instant the simulation starts at
     * @param roster          the roster of the network
     * @param eventReceivers  the event receiver of every node in the roster, in any order
     * @param echoes          whether a node receives copies of its own events that other nodes forward
     * @param broadcastConfig the production broadcast settings the delivery delays are derived from
     * @param syncConfig      the production sync settings the delivery delays are derived from
     * @throws IllegalArgumentException if the event receivers do not belong to exactly the nodes of the roster
     */
    public SimulatedNetworkConnectivity(
            @NonNull final Random random,
            @NonNull final Instant start,
            @NonNull final RosterWrapper roster,
            @NonNull final Collection<? extends EventReceiver> eventReceivers,
            final boolean echoes,
            @NonNull final BroadcastConfig broadcastConfig,
            @NonNull final SyncConfig syncConfig) {
        this.random = requireNonNull(random);
        this.start = requireNonNull(start);
        this.broadcastConfig = requireNonNull(broadcastConfig);
        this.syncConfig = requireNonNull(syncConfig);
        this.roster = requireNonNull(roster);

        final Map<NodeId, EventReceiver> receiversById = new HashMap<>();
        for (final EventReceiver eventReceiver : eventReceivers) {
            if (receiversById.put(eventReceiver.getNodeId(), eventReceiver) != null) {
                throw new IllegalArgumentException(
                        "There is more than one event receiver for " + eventReceiver.getNodeId());
            }
        }
        for (final NodeId nodeId : receiversById.keySet()) {
            if (!roster.contains(nodeId)) {
                throw new IllegalArgumentException(
                        "Node " + nodeId + " has an event receiver but is not in the roster");
            }
        }
        final int nodeCount = roster.size();
        final List<EventReceiver> receiversByRank = new ArrayList<>(nodeCount);
        for (final NodeId nodeId : roster.nodeIds()) {
            final EventReceiver eventReceiver = receiversById.get(nodeId);
            if (eventReceiver == null) {
                throw new IllegalArgumentException("Node " + nodeId + " is in the roster but has no event receiver");
            }
            receiversByRank.add(eventReceiver);
        }
        this.eventReceivers = List.copyOf(receiversByRank);

        eventWindows = new NodeEventWindows(nodeCount);
        pendingSubmissions = new PendingSubmissions(nodeCount);
        trackedEvents = new TrackedEvents(roster);
        links = new LinkStates(nodeCount, random, broadcastConfig);
        arrivalTimes = new ArrivalTimes(start, random, links, nodeCount, broadcastConfig, syncConfig);
        inFlightEvents = new InFlightEvents(nodeCount, this::isConnected, eventWindows::latest, start);
        inFlightEventWindows = new InFlightEventWindows(nodeCount, this::isConnected, start);
        inFlightInDeliveryOrder = List.of(inFlightEventWindows, inFlightEvents);
        router = new EventRouter(roster, links, arrivalTimes, inFlightEvents, trackedEvents, echoes);
    }

    /**
     * Set the connection data for this simulated network.
     *
     * <p>A link is a pair of nodes, and is up only if both directions are connected. A link that has come up is not
     * usable until a reconnect delay has passed, and its two ends are each sent the other's latest event window again,
     * as they would be when a real connection is established. The events in flight on a link that has gone down are
     * sent again by another node that has them, see {@link EventRouter#linkWentDown(int, int, long)}. See
     * {@link LinkStates#update(Map, java.util.function.ToIntFunction, long, LinkStates.LinkListener)} for how the state
     * of links that have gone down is treated, and why the first connection data has no delay.
     *
     * @param newConnections the connection data
     * @throws IllegalArgumentException if the data does not contain the connection from every node to every other node,
     *                                  or contains a connection of a node that is not part of the network
     */
    public void setConnections(@NonNull final Map<ConnectionKey, ConnectionState> newConnections) {
        // links come up in rank order, so that the random numbers drawn for them are drawn in a reproducible order
        links.update(newConnections, this::rankOf, currentTime, this);
    }

    /**
     * Called for a link that has come up.
     * <p>
     * Establishes the connection of a link that has just come up, see {@link #linkRestarted(int, int)}, and sends each
     * of its ends the events it lacks, see {@link EventRouter#linkCameUp(int, int, long)}.
     *
     * @param first  the rank of the node of the link that comes first in roster order
     * @param second the rank of the other node of the link
     */
    public void linkCameUp(final int first, final int second) {
        linkRestarted(first, second);
        router.setReceiverWindow(first, eventWindows.latest(first));
        router.setReceiverWindow(second, eventWindows.latest(second));
        router.linkCameUp(first, second, currentTime);
    }

    /**
     * Establishes the connection of a link again: starts its reconnect delay and sends each of its ends the other's
     * latest event window.
     *
     * <p>This is all a link that is up needs when one of its nodes restarts. The link was up all along, so the peer
     * lacks nothing because of it, and the restarted node is sent every event again on the next tick, see
     * {@link #replayEventLog(long)}.
     *
     * @param first  the rank of the node of the link that comes first in roster order
     * @param second the rank of the other node of the link
     */
    public void linkRestarted(final int first, final int second) {
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
    public void resendEventWindow(final int sender, final int receiver) {
        final NodeId senderId = roster.nodeIdAtIndex(sender);
        final EventWindow eventWindow = eventWindows.latest(sender);
        final Instant arrivalTime =
                arrivalTimes.decideNextArrivalTime(sender, receiver, MessageRoute.BROADCAST, currentTime);
        inFlightEventWindows.add(sender, receiver, new EventWindowInTransit(eventWindow, senderId, arrivalTime));
    }

    /**
     * Submit an event to be gossiped around the network. Safe to be called by multiple nodes in parallel.
     *
     * <p>The event is not scheduled here. Nodes run concurrently while they submit, so the order submissions arrive in
     * is decided by the thread scheduler, and scheduling draws random numbers, which has to happen in a reproducible
     * order. The event is held until the next call to {@link #tick(Instant)}, which does the scheduling on a single
     * thread.
     *
     * @param event the event to gossip, with its sender set to the node submitting it
     */
    @Override
    public void submitEvent(@NonNull final PlatformEvent event) {
        final NodeId submitter = requireNonNull(event.getSenderId(), "a submitted event must have its sender set");
        pendingSubmissions.submit(requireRankOf(submitter), event);
    }

    /**
     * Schedules the events submitted since the previous tick, one node at a time in the order drawn by
     * {@link PendingSubmissions#shuffledOrder(Random)}, and within a node in the order it submitted them. Every
     * submission is scheduled once, see {@link EventRouter#sendSubmission(TrackedEvent, int, long)}.
     *
     * <p>The order submissions are scheduled in decides which jitter value each of them draws from {@link #random} and
     * when it arrives, and which copies of an event are sent at all. Scheduling as submissions arrived would let the
     * thread scheduler decide all of that and break determinism, so the submissions are held and scheduled here
     * instead.
     *
     * @param now the time everything scheduled was sent at, the time of the previous tick, in nanoseconds since the start
     */
    private void scheduleSubmittedEvents(final long now) {
        for (int receiver = 0; receiver < roster.size(); receiver++) {
            router.setReceiverWindow(receiver, eventWindows.latest(receiver));
        }

        for (final int sender : pendingSubmissions.shuffledOrder(random)) {
            final List<PlatformEvent> events = pendingSubmissions.eventsOf(sender);
            for (final PlatformEvent event : events) {
                // The same event is submitted by its creator and again by every node that receives it and re-offers
                // it to gossip. All of those are scheduled: which node transmits an event is an important part of
                // simulating gossip. Only a node submitting the same event again is ignored.
                final TrackedEvent tracked = trackedEvents.addIfUnique(event, sender);
                if (tracked != null) {
                    for (int i = 0, n = observers.size(); i < n; i++) {
                        observers.get(i).eventSubmitted(event, sender, now);
                    }
                    router.sendSubmission(tracked, sender, now);
                }
            }
            events.clear();
        }
    }

    /**
     * Sends the tracked events again to the nodes that have restarted since the previous tick, see
     * {@link EventRouter#resendTo(TrackedEvent, int, long)}. This runs before the submissions of the current tick are
     * scheduled, which reach the restarted nodes on their own.
     *
     * @param now the time everything scheduled was sent at, the time of the previous tick, in nanoseconds since the start
     */
    private void replayEventLog(final long now) {
        for (int receiver = 0; receiver < roster.size(); receiver++) {
            if (!restartedNodes.remove(receiver)) {
                continue;
            }
            router.setReceiverWindow(receiver, eventWindows.latest(receiver));
            final Cursor<TrackedEvent> cursor = trackedEvents.newCursor();
            while (cursor.hasNext()) {
                router.resendTo(cursor.next(), receiver, now);
            }
        }
    }

    /**
     * Report the latest {@link EventWindow} of a node. Safe to be called by multiple nodes in parallel, as long as each
     * reports only its own window.
     *
     * @param nodeId      the id of the node the event window belongs to
     * @param eventWindow the node's latest event window
     */
    @Override
    public void updateEventWindow(@NonNull final NodeId nodeId, @NonNull final EventWindow eventWindow) {
        eventWindows.report(requireRankOf(nodeId), eventWindow);
    }

    /**
     * Finds the event window of the node that is furthest behind and applies it to the tracked events. Everything
     * expired for that node is expired for the whole network, and so is of no further use to anyone.
     *
     * <p>Does nothing when no node has reported a window since the last tick, since the oldest one cannot have moved.
     * The newly reported windows are taken later in the tick by {@link #scheduleEventWindows(long)}.
     */
    private void applyOldestEventWindow() {
        final EventWindow oldestEventWindow = eventWindows.oldestIfChanged();
        if (oldestEventWindow != null) {
            trackedEvents.applyOldestEventWindow(oldestEventWindow);
        }
    }

    /**
     * Move time forward to the given instant.
     *
     * @param now the new time
     */
    public void tick(@NonNull final Instant now) {
        // Everything submitted since the previous tick was sent at the time of the previous tick: the nodes run right
        // after the network in every tick, at the same instant.
        final long sendTime = currentTime;
        currentTime = InFlightQueue.nanosSince(start, now);
        // The oldest event window is applied first, so that the window the tracked events are discarded by matches the
        // range of birth rounds the log still accepts when the submissions are appended.
        applyOldestEventWindow();
        // Submissions are scheduled at the time they were sent, and before delivery, so that an event spends exactly
        // its latency in flight, rounded up to whole ticks, and not one extra tick. An event still spends at least one
        // tick in flight, because it was sent at the previous tick.
        scheduleForDelivery(sendTime);
        deliverArrived(now);
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
     * previous tick, the events of the restarted nodes, and the events submitted since the previous tick.
     *
     * <p>The event windows are scheduled first, sender by sender, and then the events. This is the order
     * {@link #deliverArrived(Instant)} follows too, which delivers the event windows to every receiver before any
     * events. The order is not incidental: everything scheduled draws an arrival time from
     * {@link #random}, and a window and an event on the same connection share the clamp in {@link ArrivalTimes}, so
     * which goes first decides the arrival times of both, and the same seed only reproduces a run if it is always the
     * same.
     *
     * @param now the time everything scheduled was sent at, the time of the previous tick, in nanoseconds since the start
     */
    private void scheduleForDelivery(final long now) {
        if (!links.connectionsInitialized()) {
            // No connections have been set, so there is nowhere to send anything.
            return;
        }

        scheduleEventWindows(now);
        replayEventLog(now);
        scheduleSubmittedEvents(now);
        for (int i = 0, n = observers.size(); i < n; i++) {
            observers.get(i).scheduled(now);
        }
    }

    /**
     * Adds an observer that follows the gossip, for an add-on such as the estimate of duplicate events. It reads the
     * state of the network through this network, as a {@link NetworkView}. Must be added before any event is
     * submitted. Observers are told about everything in the order they were added.
     *
     * @param observer the observer
     */
    public void addObserver(@NonNull final NetworkObserver observer) {
        observers.add(requireNonNull(observer));
    }

    // NetworkView: the state of this network, read by a NetworkObserver

    /**
     * {@inheritDoc}
     */
    @Override
    public int nodeCount() {
        return roster.size();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeId nodeId(final int rank) {
        return roster.nodeIdAtIndex(rank);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int rankOf(@NonNull final NodeId nodeId) {
        return roster.index(nodeId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long latency(final int sender, final int receiver) {
        return links.latency(sender, receiver);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isLinkUp(final int a, final int b) {
        return links.isLinkUp(a, b);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long usableFrom(final int a, final int b) {
        return links.usableFrom(a, b);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isBroadcastMode(final int a, final int b, final long now) {
        return links.isBroadcastMode(a, b, now);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public EventWindow eventWindow(final int rank) {
        return eventWindows.latest(rank);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long firstArrival(@NonNull final PlatformEvent event, final int node) {
        final TrackedEvent tracked = trackedEvents.find(event);
        return tracked == null ? Long.MAX_VALUE : tracked.earliestArrival(node);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public BroadcastConfig broadcastConfig() {
        return broadcastConfig;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SyncConfig syncConfig() {
        return syncConfig;
    }

    /**
     * Schedule the event windows reported since the previous tick for delivery, sender by sender, to every node the
     * sender has a connection to. A node is not sent its own window, and a node that has not reported since the last
     * tick has nothing to send, because its peers already hold the window it would send.
     *
     * <p>This takes the newly reported windows from {@link #eventWindows}.
     *
     * @param now the time everything scheduled was sent at, the time of the previous tick, in nanoseconds since the start
     */
    private void scheduleEventWindows(final long now) {
        final int nodeCount = roster.size();
        for (int sender = 0; sender < nodeCount; sender++) {
            final EventWindow eventWindow = eventWindows.takeNewlyReported(sender);
            if (eventWindow == null) {
                continue;
            }
            final NodeId senderId = roster.nodeIdAtIndex(sender);

            for (int receiver = 0; receiver < nodeCount; receiver++) {
                if (sender == receiver) {
                    continue;
                }

                // Whether the connection is up is decided at delivery time, as it is for a creator's own copy of an
                // event. A window held through a partition is superseded by the ones scheduled behind it, so when the
                // connection returns the receiver is given the sender's current window rather than a stale one.
                final Instant arrivalTime =
                        arrivalTimes.decideNextArrivalTime(sender, receiver, MessageRoute.BROADCAST, now);
                inFlightEventWindows.add(
                        sender, receiver, new EventWindowInTransit(eventWindow, senderId, arrivalTime));
            }
        }
    }

    /**
     * Called for a link that has gone down.
     * <p>
     * Sends again what was in flight on a link that has gone down, see {@link EventRouter#linkWentDown(int, int, long)}.
     *
     * @param first  the rank of the node of the link that comes first in roster order
     * @param second the rank of the other node of the link
     */
    public void linkWentDown(final int first, final int second) {
        router.setReceiverWindow(first, eventWindows.latest(first));
        router.setReceiverWindow(second, eventWindows.latest(second));
        router.linkWentDown(first, second, currentTime);
    }

    /**
     * Tells the network that a node has restarted and needs to reprocess all events from the beginning. Every event
     * that is not expired yet is sent to the node again, from the next tick on, see
     * {@link EventRouter#resendTo(TrackedEvent, int, long)}.
     *
     * <p>Everything in flight towards the node is discarded here. Each of the node's links that is up has to establish
     * its connection again, see {@link #linkRestarted(int, int)}.
     *
     * @param nodeId the id of the node that has restarted
     */
    @Override
    public void nodeRestarted(@NonNull final NodeId nodeId) {
        final int node = requireRankOf(nodeId);
        restartedNodes.add(node);
        for (final InFlightConnections<?> inFlight : inFlightInDeliveryOrder) {
            inFlight.clearIncoming(node);
        }
        links.restartLinksOf(node, this);
    }

    /**
     * Gets the rank of a node, which is its index in the roster.
     *
     * @param nodeId the id of the node
     * @return the rank of the node
     * @throws IllegalArgumentException if the node is not part of the network
     */
    private int requireRankOf(@NonNull final NodeId nodeId) {
        final int rank = roster.index(nodeId);
        if (rank < 0) {
            throw new IllegalArgumentException("Node " + nodeId + " is not part of the network");
        }
        return rank;
    }
}
