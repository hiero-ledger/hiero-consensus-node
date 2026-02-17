// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.gossip.shadowgraph;

import static org.hiero.base.CompareTo.isGreaterThan;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;

/**
 * Various static utility method used in syncing
 */
public final class SyncUtils {

    private static final Logger logger = LogManager.getLogger();

    /**
     * Private constructor to never instantiate this class
     */
    private SyncUtils() {}

    /**
     * Given a list of events we think the other node may not have, reduce that list to events that we think they do not
     * have and that are unlikely to end up being duplicate events.
     *
     * <p>
     * General principles if broadcast is disabled:
     * <ul>
     * <li>Always send self events right away.</li>
     * <li>Don't send non-ancestors of self events unless we've known about that event for a long time.</li>
     * </ul>
     *
     * <p>
     * General principles if broadcast is enabled:
     * <ul>
     * <li>Don't send ancestors of self events created by other nodes for short amount of time</li>
     * <li>Don't send non-ancestors of self events or self events themselves unless we've known about that event for a long time.</li>
     * </ul>
     *
     * @param selfId                  the id of this node
     * @param nonAncestorThreshold    for each event that is not a self event and is not an ancestor of a self event,
     *                                the amount of time the event must be known about before it is eligible to be sent
     * @param ancestorFilterThreshold for each event that is not a self event and is an ancestor of a self event, the
     *                                amount of time the event must be known about before it is eligible to be sent
     * @param selfFilterThreshold     for each event that is a self event the amount of time the event must be known
     *                                about before it is eligible to be sent
     * @param now                     the current time
     * @param eventsTheyNeed          the list of events we think they need, expected to be in topological order
     * @return the events that should be actually sent, will be a subset of the eventsTheyNeed list
     */
    @NonNull
    public static List<PlatformEvent> filterLikelyDuplicates(
            @NonNull final NodeId selfId,
            @NonNull final Duration nonAncestorThreshold,
            @NonNull final Duration ancestorFilterThreshold,
            @NonNull final Duration selfFilterThreshold,
            @NonNull final Instant now,
            @NonNull final List<PlatformEvent> eventsTheyNeed) {

        final LinkedList<PlatformEvent> filteredList = new LinkedList<>();

        final Set<Hash> parentHashesOfEventsToSend = new HashSet<>();

        // Iterate backwards over the events the peer needs, which are in topological order. This allows us to
        // find all ancestors of events we plan on sending, and to send those as well. Events are added to the
        // filtered list in reverse order, resulting in a list that is in topological order.

        for (int index = eventsTheyNeed.size() - 1; index >= 0; index--) {
            final PlatformEvent event = eventsTheyNeed.get(index);

            // if it is related to self event or its parent, use shorter time limit
            // in particular, if broadcast is disabled, that limit will be zero, so all self events and their recursive
            // parents will be sent immediately
            final Duration needsToBeAtLeastThatOld;
            final boolean alwaysRecurse;
            if (event.getCreatorId().equals(selfId)) {
                needsToBeAtLeastThatOld = selfFilterThreshold;
                alwaysRecurse = true;
            } else if (parentHashesOfEventsToSend.contains(event.getHash())) {
                needsToBeAtLeastThatOld = ancestorFilterThreshold;
                alwaysRecurse = true;
            } else {
                needsToBeAtLeastThatOld = nonAncestorThreshold;
                alwaysRecurse = false;
            }

            final boolean sendEvent = haveWeKnownAboutEventForALongTime(event, needsToBeAtLeastThatOld, now);

            if (sendEvent) {
                filteredList.addFirst(event);
            }

            // we want to have a chance of iterating parents of events we are potentially sending, even if particular
            // one won't be ultimately sent due to not fitting in time range
            // this is to cover situation where limit for self events is bigger than for their parents for example
            if (sendEvent || alwaysRecurse) {
                for (final EventDescriptorWrapper otherParent : event.getAllParents()) {
                    parentHashesOfEventsToSend.add(otherParent.hash());
                }
            }
        }

        return filteredList.stream().toList();
    }

    /**
     * Decide if we've known about an event for long enough to make it eligible to be sent.
     *
     * @param event                the event to check
     * @param nonAncestorThreshold the amount of time the event must be known about before it is eligible to be sent
     * @param now                  the current time
     * @return true if we've known about the event for long enough, false otherwise
     */
    private static boolean haveWeKnownAboutEventForALongTime(
            @NonNull final PlatformEvent event,
            @NonNull final Duration nonAncestorThreshold,
            @NonNull final Instant now) {
        final Instant eventReceivedTime = event.getTimeReceived();
        final Duration timeKnown = Duration.between(eventReceivedTime, now);
        return isGreaterThan(timeKnown, nonAncestorThreshold);
    }

    /**
     * Returns a predicate that determines if a {@link ShadowEvent}'s ancient indicator is non-ancient for the peer and
     * greater than this node's minimum non-expired threshold, and is not already known.
     *
     * @param knownShadows     the {@link ShadowEvent}s that are already known and should therefore be rejected by the
     *                         predicate
     * @param myEventWindow    the event window of this node
     * @param theirEventWindow the event window of the peer node
     * @return the predicate
     */
    @NonNull
    public static Predicate<ShadowEvent> unknownNonAncient(
            @NonNull final Collection<ShadowEvent> knownShadows,
            @NonNull final EventWindow myEventWindow,
            @NonNull final EventWindow theirEventWindow) {

        // When searching for events, we don't want to send any events that are known to be ancient to the peer.
        // We should never be syncing with a peer if their ancient threshold is less than our expired threshold
        // (if this is the case, then the peer is "behind"), so in practice the minimumSearchThreshold will always
        // be the same as the peer's ancient threshold. However, in an abundance of caution, we use the maximum of
        // the two thresholds to ensure that we don't ever attempt to traverse over events that are expired to us,
        // since those events may be unlinked and could cause race conditions if accessed.

        final long minimumSearchThreshold =
                Math.max(myEventWindow.expiredThreshold(), theirEventWindow.ancientThreshold());
        return s -> s.getPlatformEvent().getBirthRound() >= minimumSearchThreshold && !knownShadows.contains(s);
    }

    /**
     * Computes the number of creators that have more than one tip. If a single creator has more than two tips, this
     * method will only report once for each such creator. The execution time cost for this method is O(T + N) where T
     * is the number of tips including all branches and N is the number of network nodes. There is some memory overhead,
     * but it is fairly nominal in favor of the time complexity savings.
     *
     * @return the number of event creators that have more than one tip.
     */
    public static int computeMultiTipCount(final Iterable<ShadowEvent> tips) {
        // The number of tips per creator encountered when iterating over the sending tips
        final Map<NodeId, Integer> tipCountByCreator = new HashMap<>();

        // Make a single O(N) where N is the number of tips including all branches. Typically, N will be equal to the
        // number of network nodes.
        for (final ShadowEvent tip : tips) {
            tipCountByCreator.compute(tip.getPlatformEvent().getCreatorId(), (k, v) -> (v != null) ? (v + 1) : 1);
        }

        // Walk the entrySet() which is O(N) where N is the number network nodes. This is still more efficient than a
        // O(N^2) loop.
        int creatorsWithBranches = 0;
        for (final Map.Entry<NodeId, Integer> entry : tipCountByCreator.entrySet()) {
            // If the number of tips for a given creator is greater than 1 then we might have a branch.
            // This map is broken down by creator ID already as the key so this is guaranteed to be a single increment
            // for each creator with a branch. Therefore, this holds to the method contract.
            if (entry.getValue() > 1) {
                creatorsWithBranches++;
            }
        }

        return creatorsWithBranches; // total number of unique creators with more than one tip
    }

    /**
     * Performs a topological sort on the given list of events (i.e. where parents always come before their children).
     *
     * @param sendList The list of events to sort.
     */
    static void sort(@NonNull final List<PlatformEvent> sendList) {
        // Note: regardless of ancient mode, sorting uses generations and not birth rounds.
        //       Sorting by generations yields a list in topological order, sorting by birth rounds does not.
        sendList.sort(Comparator.comparingLong(PlatformEvent::getNGen));
    }

    /**
     * For each tip they send us, determine if we have that event. For each tip, send true if we have the event and
     * false if we don't.
     *
     * @param theirTipShadows the tips they sent us
     * @return a list of booleans corresponding to their tips in the order they were sent. True if we have the event,
     * false if we don't
     */
    @NonNull
    static List<Boolean> getTheirTipsIHave(@NonNull final List<ShadowEvent> theirTipShadows) {
        final List<Boolean> myBooleans = new ArrayList<>(theirTipShadows.size());
        for (final ShadowEvent s : theirTipShadows) {
            myBooleans.add(s != null); // is this event is known to me
        }
        return myBooleans;
    }

    /**
     * For each tip sent to the peer, determine if they have that event. If they have it, add it to the list that is
     * returned.
     *
     * @param peerId         the peer node id
     * @param myTips         the tips we sent them
     * @param myTipsTheyHave a list of booleans corresponding to our tips in the order they were sent. True if they have
     *                       the event, false if they don't
     * @return a list of tips that they have
     */
    @NonNull
    static List<ShadowEvent> getMyTipsTheyKnow(
            @NonNull final NodeId peerId,
            @NonNull final List<ShadowEvent> myTips,
            @NonNull final List<Boolean> myTipsTheyHave) {

        Objects.requireNonNull(peerId);

        if (myTipsTheyHave.size() != myTips.size()) {
            throw new RuntimeException(String.format(
                    "during sync with %s, peer booleans list is wrong size. Expected: %d Actual: %d,",
                    peerId, myTips.size(), myTipsTheyHave.size()));
        }
        return getMyTipsTheyKnow(myTips, myTipsTheyHave);
    }

    @NonNull
    private static List<ShadowEvent> getMyTipsTheyKnow(
            @NonNull final List<ShadowEvent> myTips, @NonNull final List<Boolean> myTipsTheyHave) {
        final List<ShadowEvent> knownTips = new ArrayList<>();
        // process their booleans
        for (int i = 0; i < myTipsTheyHave.size(); i++) {
            if (Boolean.TRUE.equals(myTipsTheyHave.get(i))) {
                knownTips.add(myTips.get(i));
            }
        }

        return knownTips;
    }
}
