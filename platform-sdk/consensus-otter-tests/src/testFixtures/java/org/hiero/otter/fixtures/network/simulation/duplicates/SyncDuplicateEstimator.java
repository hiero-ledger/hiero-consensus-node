// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation.duplicates;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.otter.fixtures.network.simulation.NetworkObserver;
import org.hiero.otter.fixtures.network.simulation.NetworkView;

/**
 * Estimates the duplicate copies of events that production gossip would send to some nodes by replaying the syncs
 * between them and every other node, see {@link DuplicateTracking}.
 *
 * <p>A copy that reaches a node after another copy of the same event, or a copy of the node's own event, is a duplicate,
 * whichever of the two was sent first. The copies are counted, not delivered: the simulated network still delivers each
 * event once, and the nodes learn what they know from that.
 *
 * <h2>What the nodes know</h2>
 *
 * <p>A node knows an event from the time it submits it to the network, which a simulated node does once the event has
 * passed its intake, including waiting in the orphan buffer for its parents, or once it has created it. In production
 * that is when the event is added to the shadowgraph, which syncs read what a node knows from. The rest of the intake
 * takes production well under a millisecond, so it is left out. For the hold-back of sync, a node has known an event
 * since the first copy reached it, see {@link NetworkView#firstArrival(PlatformEvent, int)}.
 *
 * <h2>Broadcast</h2>
 *
 * <p>The creator of an event broadcasts it, when it submits it, to every node whose link to it is up and in broadcast
 * mode. Its arrival is taken without jitter.
 *
 * <h2>Syncs</h2>
 *
 * <p>The syncs of two nodes run back to back. The nodes take turns starting them: the one that started a sync finishes
 * it last, when the events of the other arrive, so the other is the first to be ready for the next one, which it starts
 * once it has paused for {@code rpcSleepAfterSyncWhileBroadcasting}, or {@code rpcSleepAfterSync} while the link is not
 * in broadcast mode. In a sync, as in production:
 * <ol>
 *     <li>Each node sends its tips, the events it knows that no other event it knows has as a parent. The node that
 *     starts the sync samples them first; the other once they have arrived, when it also answers which of them it
 *     knows. The first answers when the tips of the other arrive.</li>
 *     <li>A node takes the other to know an event if it is, or is an ancestor of, a tip of the other that the node
 *     knows when it reads the tips, or a tip of its own that the other answers it knows.</li>
 *     <li>Once the answer of the other has arrived, the node makes a list of the events it knows that the other is not
 *     taken to know and that are not ancient for the other, and filters it as {@code filterLikelyDuplicates} does: an
 *     event is sent if the node has known it for longer than {@code selfFilterThreshold} if the node created it,
 *     {@code ancestorFilterThreshold} if it is a parent of an event that is sent or that the node created, and
 *     {@code nonAncestorFilterThreshold} otherwise. While the link is not in broadcast mode, the first two are zero.</li>
 * </ol>
 * A sync can only be replayed once everything it depends on has been submitted, which is when its sender makes its
 * list, so syncs are replayed once that time has been scheduled, see {@link #scheduled(long)}.
 *
 * <h2>Not modelled</h2>
 *
 * <p>Restarts, syncs cut short when a link goes down, the limit on the number of events per sync, the permits that
 * limit the number of concurrent syncs, and the time it takes to send the events are not modelled. A sync is skipped
 * while its link is down.
 *
 * <p>Nodes are identified by their rank, which is their index in the roster. Times are nanoseconds since the start of
 * the simulation.
 *
 * <p>This class is not safe to use from more than one thread.
 */
final class SyncDuplicateEstimator implements NetworkObserver {

    /**
     * Stands for the start of the next sync of a pair of nodes, before the first sync is scheduled.
     */
    private static final long NOT_SCHEDULED = Long.MIN_VALUE;

    /**
     * The network the duplicates are estimated for.
     */
    private final NetworkView view;

    /**
     * The events that have been submitted.
     */
    private final GossipGraph graph;

    /**
     * Whether the duplicates are estimated for a node, indexed by rank.
     */
    private final boolean[] estimatedFor;

    /**
     * Is told about the duplicates.
     */
    private final DuplicateListener listener;

    /**
     * {@code rpcSleepAfterSyncWhileBroadcasting}, in nanoseconds.
     */
    private final long pauseWhileBroadcasting;

    /**
     * {@code rpcSleepAfterSync}, in nanoseconds.
     */
    private final long pause;

    /**
     * {@code selfFilterThreshold}, in nanoseconds.
     */
    private final long selfThreshold;

    /**
     * {@code ancestorFilterThreshold}, in nanoseconds.
     */
    private final long ancestorThreshold;

    /**
     * {@code nonAncestorFilterThreshold}, in nanoseconds.
     */
    private final long nonAncestorThreshold;

    /**
     * The time the next sync between two nodes starts, indexed by {@code sender * nodeCount + receiver}, where the
     * receiver is a node the duplicates are estimated for, or {@link #NOT_SCHEDULED}.
     */
    private final long[] nextSyncStarts;

    /**
     * Whether the next sync between two nodes is started by the receiver, indexed like {@link #nextSyncStarts}.
     */
    private final boolean[] receiverStartsNextSync;

    /**
     * The events the sender of the current sync knows and that are not ancient for the receiver, in topological order,
     * kept to be reused.
     */
    private final List<GraphEvent> candidates = new ArrayList<>();

    /**
     * The events still to be visited by the current search through the graph of events, kept to be reused.
     */
    private final ArrayDeque<GraphEvent> toVisit = new ArrayDeque<>();

    /**
     * The number of the current sync, see {@link GraphEvent#visit(int)}.
     */
    private int sync;

    /**
     * Constructor.
     *
     * @param view         the network the duplicates are estimated for
     * @param estimatedFor whether the duplicates are estimated for a node, indexed by rank
     * @param listener     is told about the duplicates
     */
    SyncDuplicateEstimator(
            @NonNull final NetworkView view,
            @NonNull final boolean[] estimatedFor,
            @NonNull final DuplicateListener listener) {
        this.view = requireNonNull(view);
        this.estimatedFor = estimatedFor.clone();
        this.listener = requireNonNull(listener);
        final int nodeCount = view.nodeCount();
        this.graph = new GossipGraph(nodeCount);
        this.pauseWhileBroadcasting =
                view.broadcastConfig().rpcSleepAfterSyncWhileBroadcasting().toNanos();
        this.pause = view.syncConfig().rpcSleepAfterSync().toNanos();
        this.selfThreshold = view.syncConfig().selfFilterThreshold().toNanos();
        this.ancestorThreshold = view.syncConfig().ancestorFilterThreshold().toNanos();
        this.nonAncestorThreshold =
                view.syncConfig().nonAncestorFilterThreshold().toNanos();
        this.nextSyncStarts = new long[nodeCount * nodeCount];
        Arrays.fill(nextSyncStarts, NOT_SCHEDULED);
        this.receiverStartsNextSync = new boolean[nodeCount * nodeCount];
    }

    /**
     * {@inheritDoc}
     *
     * <p>The node knows the event from now. If it created the event, it broadcasts it.
     */
    @Override
    public void eventSubmitted(@NonNull final PlatformEvent event, final int submitter, final long now) {
        final GraphEvent added = graph.add(event, view.rankOf(event.getCreatorId()));
        added.learnedBy(submitter, now);
        if (submitter != added.creator()) {
            return;
        }
        for (int receiver = 0; receiver < estimatedFor.length; receiver++) {
            if (estimatedFor[receiver]
                    && receiver != submitter
                    && view.isLinkUp(submitter, receiver)
                    && view.isBroadcastMode(submitter, receiver, now)
                    && added.birthRound() >= view.eventWindow(receiver).ancientThreshold()) {
                final long arrival =
                        Math.max(now + view.latency(submitter, receiver), view.usableFrom(submitter, receiver));
                copyArrives(added, receiver, arrival, true);
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Forgets the events that are expired for every node, and replays every sync whose sender makes its list by now.
     */
    @Override
    public void scheduled(final long now) {
        long oldestExpiredThreshold = Long.MAX_VALUE;
        for (int rank = 0; rank < estimatedFor.length; rank++) {
            oldestExpiredThreshold =
                    Math.min(oldestExpiredThreshold, view.eventWindow(rank).expiredThreshold());
        }
        graph.forgetExpired(oldestExpiredThreshold);

        for (int receiver = 0; receiver < estimatedFor.length; receiver++) {
            if (!estimatedFor[receiver]) {
                continue;
            }
            for (int sender = 0; sender < estimatedFor.length; sender++) {
                if (sender != receiver) {
                    runSyncsUpTo(sender, receiver, now);
                }
            }
        }
    }

    /**
     * Replays the syncs between two nodes whose sender makes its list by the given time.
     *
     * @param sender   the rank of the node that sends events to the receiver
     * @param receiver the rank of a node the duplicates are estimated for
     * @param now      the current time
     */
    private void runSyncsUpTo(final int sender, final int receiver, final long now) {
        final int index = sender * estimatedFor.length + receiver;
        if (nextSyncStarts[index] == NOT_SCHEDULED) {
            nextSyncStarts[index] = now;
            receiverStartsNextSync[index] = receiver < sender;
        }
        while (true) {
            if (!view.isLinkUp(sender, receiver)) {
                // no syncs while the link is down; the next one starts once it is up
                nextSyncStarts[index] = Math.max(nextSyncStarts[index], now);
                return;
            }
            final long start = Math.max(nextSyncStarts[index], view.usableFrom(sender, receiver));
            final long latencyThere = view.latency(sender, receiver);
            final long latencyBack = view.latency(receiver, sender);
            final long roundTrip = latencyThere + latencyBack;
            final boolean startedByReceiver = receiverStartsNextSync[index];

            final long senderTips;
            final long receiverTips;
            final long senderReadsTips;
            final long receiverAnswers;
            final long sendListMade;
            final long otherFinishes;
            if (startedByReceiver) {
                receiverTips = start;
                senderTips = start + latencyBack;
                senderReadsTips = senderTips;
                receiverAnswers = start + roundTrip;
                sendListMade = receiverAnswers + latencyBack;
                // the sender started last, and finishes once the receiver's events arrive
                otherFinishes = receiverAnswers + latencyBack;
            } else {
                senderTips = start;
                receiverTips = start + latencyThere;
                receiverAnswers = receiverTips;
                senderReadsTips = start + roundTrip;
                sendListMade = senderReadsTips;
                // the receiver finishes once the sender's events arrive
                otherFinishes = sendListMade + latencyThere;
            }
            if (sendListMade > now) {
                nextSyncStarts[index] = start;
                return;
            }

            final boolean broadcastMode = view.isBroadcastMode(sender, receiver, start);
            replaySync(
                    sender,
                    receiver,
                    senderTips,
                    receiverTips,
                    senderReadsTips,
                    receiverAnswers,
                    sendListMade,
                    sendListMade + latencyThere,
                    broadcastMode);
            nextSyncStarts[index] = otherFinishes + (broadcastMode ? pauseWhileBroadcasting : pause);
            receiverStartsNextSync[index] = !startedByReceiver;
        }
    }

    /**
     * Replays what one sync sends from the sender to the receiver.
     *
     * @param sender          the rank of the node that sends events
     * @param receiver        the rank of the node the events are sent to
     * @param senderTips      the time the sender samples its tips
     * @param receiverTips    the time the receiver samples its tips
     * @param senderReadsTips the time the sender reads the receiver's tips
     * @param receiverAnswers the time the receiver answers which of the sender's tips it knows
     * @param sendListMade    the time the sender makes its list of events to send
     * @param arrival         the time the events arrive at the receiver
     * @param broadcastMode   whether the link is in broadcast mode, which decides the thresholds of the filter
     */
    private void replaySync(
            final int sender,
            final int receiver,
            final long senderTips,
            final long receiverTips,
            final long senderReadsTips,
            final long receiverAnswers,
            final long sendListMade,
            final long arrival,
            final boolean broadcastMode) {
        sync++;
        final long ancientThreshold = view.eventWindow(receiver).ancientThreshold();

        // the events the sender knows, and the tips that show it what the receiver knows, which it is taken to know
        // with all their ancestors
        candidates.clear();
        toVisit.clear();
        for (final GraphEvent event : graph.inOrder()) {
            if (event.birthRound() < ancientThreshold) {
                // an ancient event is not sent, and neither are its ancestors, which are ancient too
                continue;
            }
            if (event.isKnownBy(sender, sendListMade)) {
                candidates.add(event);
            }
            if (((isTip(event, receiver, receiverTips) && event.isKnownBy(sender, senderReadsTips))
                            || (isTip(event, sender, senderTips) && event.isKnownBy(receiver, receiverAnswers)))
                    && event.visit(sync)) {
                toVisit.add(event);
            }
        }
        GraphEvent visited;
        while ((visited = toVisit.poll()) != null) {
            for (final GraphEvent parent : visited.parents()) {
                if (parent.birthRound() >= ancientThreshold && parent.visit(sync)) {
                    toVisit.add(parent);
                }
            }
        }

        // the filter, children first, so that a parent of an event to send is marked before it is reached
        final long self = broadcastMode ? selfThreshold : 0;
        final long ancestor = broadcastMode ? ancestorThreshold : 0;
        for (int i = candidates.size() - 1; i >= 0; i--) {
            final GraphEvent event = candidates.get(i);
            if (event.wasVisited(sync)) {
                continue;
            }
            final boolean own = event.creator() == sender;
            final boolean parentToSend = event.isMarkedAsParentToSend(sync);
            final long threshold = own ? self : (parentToSend ? ancestor : nonAncestorThreshold);
            final boolean send = sendListMade - receivedAt(event, sender) > threshold;
            if (send) {
                copyArrives(event, receiver, arrival, false);
            }
            if (send || own || parentToSend) {
                for (final GraphEvent parent : event.parents()) {
                    parent.markAsParentToSend(sync);
                }
            }
        }
    }

    /**
     * Reports whether an event is a tip of a node at a given time: the node knows it, and no event the node knows has it
     * as a parent.
     *
     * @param event the event
     * @param node  the rank of the node
     * @param time  the time
     * @return {@code true} if the event is a tip of the node
     */
    private static boolean isTip(@NonNull final GraphEvent event, final int node, final long time) {
        if (!event.isKnownBy(node, time)) {
            return false;
        }
        for (final GraphEvent child : event.children()) {
            if (child.isKnownBy(node, time)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Gets the time a node has known an event since, for the filter, which is when the first copy reached it, or when
     * it created the event.
     *
     * @param event the event
     * @param node  the rank of the node
     * @return the time
     */
    private long receivedAt(@NonNull final GraphEvent event, final int node) {
        return Math.min(view.firstArrival(event.event(), node), event.knownFrom(node));
    }

    /**
     * Records a copy of an event that reaches a node, and tells the listener if it is a duplicate. Of all the copies of
     * an event that reach a node, every one but the one that arrives first is a duplicate, and every copy of the node's
     * own event is.
     *
     * @param event     the event
     * @param receiver  the rank of the node
     * @param arrival   the time the copy arrives
     * @param broadcast whether the copy is broadcast
     */
    private void copyArrives(
            @NonNull final GraphEvent event, final int receiver, final long arrival, final boolean broadcast) {
        final boolean duplicateBroadcast;
        if (event.creator() == receiver) {
            duplicateBroadcast = broadcast;
        } else {
            final long first = event.firstCopyArrival(receiver);
            if (first == GraphEvent.NEVER) {
                event.setFirstCopy(receiver, arrival, broadcast);
                return;
            }
            if (arrival < first) {
                // this copy overtakes the one that was first so far, which becomes the duplicate
                duplicateBroadcast = event.isFirstCopyBroadcast(receiver);
                event.setFirstCopy(receiver, arrival, broadcast);
            } else {
                duplicateBroadcast = broadcast;
            }
        }
        listener.onDuplicates(
                view.nodeId(receiver), event.event(), duplicateBroadcast ? 0 : 1, duplicateBroadcast ? 1 : 0);
    }
}
