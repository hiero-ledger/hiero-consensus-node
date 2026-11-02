// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.BitSet;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.RosterWrapper;

/**
 * Decides which copies of an event are put onto the simulated network, from which node, and when they arrive.
 *
 * <p>A copy that the creator of an event sends is always sent. A copy that another node forwards is only sent if it
 * arrives before every copy already on its way to the receiver, see
 * {@link #sendSubmission(TrackedEvent, PlatformEvent, int, long)}. When a link goes down, or a node restarts, what was
 * on its way is sent again by the node that has it and can deliver it soonest.
 *
 * <p>Nodes are identified by their rank, which is their index in the roster. Times are nanoseconds since the start of
 * the simulation.
 *
 * <p>This class is not safe to use from more than one thread.
 */
final class EventRouter {

    /**
     * The roster of the network.
     */
    private final RosterWrapper roster;

    /**
     * The state of every connection and link.
     */
    private final LinkStates links;

    /**
     * Picks the arrival times of the copies that are sent.
     */
    private final ArrivalTimes arrivalTimes;

    /**
     * Where the copies that are sent are put.
     */
    private final InFlightEvents inFlightEvents;

    /**
     * The events that have been submitted, which re-routing looks the events in flight up in.
     */
    private final TrackedEvents trackedEvents;

    /**
     * Whether a node is sent copies of its own events that other nodes forward.
     */
    private final boolean echoes;

    /**
     * The event window of each node, indexed by rank, as last given to {@link #setReceiverWindow(int, EventWindow)}.
     * An event that is ancient for a node is not sent to it.
     */
    private final EventWindow[] receiverWindows;

    /**
     * Constructor.
     *
     * @param roster         the roster of the network
     * @param links          the state of every connection and link
     * @param arrivalTimes   picks the arrival times of the copies that are sent
     * @param inFlightEvents where the copies that are sent are put
     * @param trackedEvents  the events that have been submitted
     * @param echoes         whether a node is sent copies of its own events that other nodes forward
     */
    EventRouter(
            @NonNull final RosterWrapper roster,
            @NonNull final LinkStates links,
            @NonNull final ArrivalTimes arrivalTimes,
            @NonNull final InFlightEvents inFlightEvents,
            @NonNull final TrackedEvents trackedEvents,
            final boolean echoes) {
        this.roster = requireNonNull(roster);
        this.links = requireNonNull(links);
        this.arrivalTimes = requireNonNull(arrivalTimes);
        this.inFlightEvents = requireNonNull(inFlightEvents);
        this.trackedEvents = requireNonNull(trackedEvents);
        this.echoes = echoes;
        this.receiverWindows = new EventWindow[roster.size()];
        for (int rank = 0; rank < receiverWindows.length; rank++) {
            receiverWindows[rank] = EventWindow.getGenesisEventWindow();
        }
    }

    /**
     * Sets the event window of a node that decides which events are ancient for it. The caller sets it before anything
     * is sent to the node, so that all of it is judged against the same window.
     *
     * @param receiver the rank of the node
     * @param window   the event window of the node
     */
    void setReceiverWindow(final int receiver, @NonNull final EventWindow window) {
        receiverWindows[receiver] = window;
    }

    /**
     * Sends the copies of a submission to every node they are of use to, see
     * {@link #sendCopy(TrackedEvent, PlatformEvent, int, int, long)}.
     *
     * @param tracked the event, as tracked by the network
     * @param event   the submitted instance of the event
     * @param sender  the rank of the node that submitted the event
     * @param now     the current time
     */
    void sendSubmission(
            @NonNull final TrackedEvent tracked, @NonNull final PlatformEvent event, final int sender, final long now) {
        for (int receiver = 0; receiver < receiverWindows.length; receiver++) {
            sendCopy(tracked, event, sender, receiver, now);
        }
    }

    /**
     * Sends an event again to a node that has restarted. Everything that was on its way to the node is gone, so the
     * event is sent once, by the node that has it and can deliver it soonest, see
     * {@link #sendBestCopy(TrackedEvent, int, long, boolean)}. A link that is down does not rule a node out, because
     * what is sent is held until the link comes back.
     *
     * @param tracked  the event, as tracked by the network
     * @param receiver the rank of the node that has restarted
     * @param now      the current time
     */
    void resendTo(@NonNull final TrackedEvent tracked, final int receiver, final long now) {
        tracked.resetEarliest(receiver);
        sendBestCopy(tracked, receiver, now, false);
    }

    /**
     * Sends again what was in flight on a link that has gone down, from the nodes that have it and can deliver it
     * soonest.
     *
     * <p>Only an event whose earliest copy on its way to the receiver was on the link needs to be sent again. Others are
     * on their way by another route, or were never counted as on their way, because the creator sent them while the
     * link was already down. What cannot be sent again, because no node that has it has a link to the receiver that is
     * up, stays in flight and is held until the link comes back.
     *
     * @param first  the rank of the node of the link that comes first in roster order
     * @param second the rank of the other node of the link
     * @param now    the current time
     */
    void linkWentDown(final int first, final int second, final long now) {
        rerouteInFlight(first, second, now);
        rerouteInFlight(second, first, now);
    }

    /**
     * Sends again what was in flight from a sender to a receiver, see {@link #linkWentDown(int, int, long)}.
     *
     * @param sender   the rank of the node at the sending end of the connection
     * @param receiver the rank of the node at the receiving end of the connection
     * @param now      the current time
     */
    private void rerouteInFlight(final int sender, final int receiver, final long now) {
        for (final EventInTransit inTransit : inFlightEvents.drain(sender, receiver)) {
            final TrackedEvent tracked = trackedEvents.find(inTransit.event());
            if (tracked != null && tracked.earliestSender(receiver) == sender) {
                tracked.resetEarliest(receiver);
                if (sendBestCopy(tracked, receiver, now, true)) {
                    continue;
                }
            }
            inFlightEvents.add(sender, receiver, inTransit);
        }
    }

    /**
     * Sends a copy of an event, as sent by a node, to another node if it is of use there.
     *
     * <p>A node does not send an event to itself. Whether a node is sent copies of its own events is up to
     * {@link #echoes}. An event that is already ancient for the receiver is not sent, because the receiver would
     * discard it.
     *
     * <p>A copy that the creator of the event sends is always sent, because it is the broadcast the other copies are
     * measured against. A copy that another node forwards is only sent if it arrives before every copy already on its
     * way to the receiver, because the receiver has no use for it otherwise. The copies on their way are the creator's
     * own, unless the link it travels on is down, and the forwarded ones sent before. Forwarded copies are sent in
     * the order the submissions are scheduled in, and the one that arrives first is the one that is kept.
     * A forwarded copy over a link that is down is not sent, as it would not arrive before the link comes back.
     * A copy that the creator sends over a link that is down is held, and not counted as on its way.
     *
     * <p>When the event arrives is decided by {@link ArrivalTimes#propose(int, int, MessageRoute, long)}, which draws
     * from the random number generator. The draw is skipped for a forwarded copy that cannot arrive first whatever it
     * draws.
     *
     * @param tracked  the event, as tracked by the network
     * @param event    the event to send
     * @param sender   the rank of the node the event is sent from
     * @param receiver the rank of the node the event is traveling to
     * @param now      the current time
     */
    private void sendCopy(
            final TrackedEvent tracked,
            final PlatformEvent event,
            final int sender,
            final int receiver,
            final long now) {
        // Don't send a node's own events back to it
        if (receiver == sender) {
            return;
        }

        // The receiver is only sent copies of its own events if echoes are on
        final int creator = tracked.creator();
        if (receiver == creator && !echoes) {
            return;
        }

        // The receiver would discard this event on arrival, so there is no point in transmitting it
        if (receiverWindows[receiver].isAncient(event)) {
            return;
        }

        // The sender and receiver may be known to each other, in which case the event is enqueued for delivery.
        // Whether the connection is up is decided at delivery time rather than here: the creator's copy is held
        // through a partition, so that it is delivered when the connection returns.
        final boolean own = sender == creator;
        final boolean linkUp = links.isLinkUp(sender, receiver);
        final MessageRoute route = routeOf(own, sender, receiver, now);
        final long earliestArrival = tracked.earliestArrival(receiver);
        if (!own) {
            if (!linkUp) {
                return;
            }
            final long earliestPossible = arrivalTimes.earliestPossible(sender, receiver, route, now);
            if (Math.max(earliestPossible, links.usableFrom(sender, receiver)) >= earliestArrival) {
                return;
            }
        }

        final long arrival = arrivalTimes.propose(sender, receiver, route, now);
        // a link that has come up recently does not deliver anything before its reconnect delay has passed
        final long deliveredAt = linkUp ? Math.max(arrival, links.usableFrom(sender, receiver)) : TrackedEvent.NOT_SENT;
        if (!own && deliveredAt >= earliestArrival) {
            return;
        }
        arrivalTimes.commit(sender, receiver, route, arrival);
        if (deliveredAt < earliestArrival) {
            tracked.setEarliest(receiver, sender, deliveredAt);
        }
        putInFlight(tracked.event(), sender, receiver, arrivalTimes.instantOf(arrival));
    }

    /**
     * Sends a node a copy of an event from the node that has it and can deliver it soonest. Whatever else is on its way
     * to the node is not taken into account, so the caller forgets it first if it is gone.
     *
     * <p>A node can deliver an event soonest if it has the most favorable average arrival time, jitter aside. Nodes
     * whose link to the receiver is up are preferred to those whose link is down, which hold the copy until the link
     * comes back. Of equally good nodes the one that comes first in roster order is chosen.
     *
     * @param tracked  the event, as tracked by the network
     * @param receiver the rank of the node the event is traveling to
     * @param now      the current time
     * @param onlyIfUp whether to give up if there is no node whose link to the receiver is up
     * @return {@code true} if a copy was sent
     */
    private boolean sendBestCopy(
            final TrackedEvent tracked, final int receiver, final long now, final boolean onlyIfUp) {
        final int creator = tracked.creator();
        if (receiver == creator && !echoes) {
            return false;
        }
        final PlatformEvent event = tracked.event();
        if (receiverWindows[receiver].isAncient(event)) {
            return false;
        }

        int best = -1;
        boolean bestUp = false;
        long bestArrival = 0;
        final BitSet holders = tracked.submitters();
        for (int holder = holders.nextSetBit(0); holder >= 0; holder = holders.nextSetBit(holder + 1)) {
            if (holder == receiver) {
                continue;
            }
            final boolean up = links.isLinkUp(holder, receiver);
            if (onlyIfUp && !up) {
                continue;
            }
            final MessageRoute route = routeOf(holder == creator, holder, receiver, now);
            final long expected = arrivalTimes.expected(holder, receiver, route, now);
            final long arrival = up ? Math.max(expected, links.usableFrom(holder, receiver)) : expected;
            if (best < 0 || (up && !bestUp) || (up == bestUp && arrival < bestArrival)) {
                best = holder;
                bestUp = up;
                bestArrival = arrival;
            }
        }
        if (best < 0) {
            return false;
        }

        final MessageRoute route = routeOf(best == creator, best, receiver, now);
        final long arrival = arrivalTimes.propose(best, receiver, route, now);
        arrivalTimes.commit(best, receiver, route, arrival);
        if (bestUp) {
            tracked.setEarliest(receiver, best, Math.max(arrival, links.usableFrom(best, receiver)));
        }
        putInFlight(event, best, receiver, arrivalTimes.instantOf(arrival));
        return true;
    }

    /**
     * Gets the route a copy sent by a node takes: broadcast if the sender created the event and the link is in
     * broadcast mode, sync otherwise.
     *
     * @param own      whether the sender created the event
     * @param sender   the rank of the node the event is sent from
     * @param receiver the rank of the node the event is traveling to
     * @param now      the current time
     * @return the route
     */
    private MessageRoute routeOf(final boolean own, final int sender, final int receiver, final long now) {
        return own && links.isBroadcastMode(sender, receiver, now) ? MessageRoute.BROADCAST : MessageRoute.SYNC;
    }

    /**
     * Puts a copy of an event in flight.
     *
     * @param event       the event to send, of which a copy is made so that nodes don't modify each other's events
     * @param sender      the rank of the node the event is sent from
     * @param receiver    the rank of the node the event is traveling to
     * @param arrivalTime the time the event arrives
     */
    private void putInFlight(
            final PlatformEvent event, final int sender, final int receiver, final Instant arrivalTime) {
        final NodeId senderId = roster.nodeIdAtIndex(sender);
        final PlatformEvent eventToDeliver = event.copyGossipedData();
        eventToDeliver.setSenderId(senderId);
        eventToDeliver.setTimeReceived(arrivalTime);
        inFlightEvents.add(sender, receiver, new EventInTransit(eventToDeliver, senderId, arrivalTime));
    }
}
