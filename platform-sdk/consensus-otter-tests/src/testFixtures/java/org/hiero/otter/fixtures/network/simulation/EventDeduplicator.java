// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.consensus.model.sequence.map.SequenceMap;
import org.hiero.consensus.model.sequence.map.StandardSequenceMap;

/**
 * Recognizes events that have already been submitted to the simulated network, and keeps a {@link TrackedEvent} of each
 * of them.
 *
 * <p>A node re-offers events to gossip after they have been through its intake pipeline, so the same event is submitted
 * once by the node that created it and again by every node that receives it. Each of those submissions is a distinct
 * offer to the network: it records that the submitting node has the event, and a node can only transmit it over its
 * own connections. Recording only the node that happened to submit an event first would confine the event to that
 * node's connections, and it would never reach a node that is only reachable by relay.
 *
 * <p>An event is therefore only a duplicate when the <i>same</i> node offers it twice. Uniqueness is defined the same
 * way as it is in the production deduplicator, with the submitting node added: a submission is a duplicate only when
 * the event's descriptor - which carries the event's hash - its signature, and the submitter have all been observed
 * together before. A descriptor seen with a signature that has not accompanied it before is a distinct event rather
 * than a duplicate.
 *
 * <p>One {@link TrackedEvent} is kept per descriptor and signature, with the submitters recorded as a bit set indexed
 * by rank, which is the index of the submitter in the roster.
 *
 * <p>This class is not safe to use from more than one thread. It is only used from the single-threaded phase of a
 * tick, after the nodes that submit events have stopped running.
 */
public class EventDeduplicator {

    /**
     * Avoid the creation of lambdas for {@link SequenceMap#computeIfAbsent} by reusing this lambda.
     */
    @NonNull
    private static final Function<EventDescriptorWrapper, List<TrackedEvent>> NEW_LIST = ignored -> new ArrayList<>(1);

    /**
     * The number of birth rounds tracked at once. The map expands beyond this as birth rounds climb.
     */
    private static final int INITIAL_CAPACITY = 1024;

    /**
     * The roster of the network, whose indices the creators and submitters of the events are identified by.
     */
    private final RosterWrapper roster;

    /**
     * A map from event descriptor to the events that have been observed for that descriptor.
     */
    private final SequenceMap<EventDescriptorWrapper, List<TrackedEvent>> observedEvents = new StandardSequenceMap<>(
            ConsensusConstants.ROUND_FIRST, INITIAL_CAPACITY, true, EventDescriptorWrapper::birthRound);

    /**
     * The event window of the node that is furthest behind, which bounds what the rest of the network still needs.
     */
    @NonNull
    private EventWindow oldestEventWindow = EventWindow.getGenesisEventWindow();

    /**
     * The expired threshold that {@link #observedEvents} has been shifted to. The window of a {@link SequenceMap} only
     * moves towards larger values, but the oldest event window in the network can move backwards when a node restarts
     * or when a node is added to a network that is already running, so the two are tracked separately.
     */
    private long shiftedThroughBirthRound = ConsensusConstants.ROUND_FIRST;

    /**
     * Constructor.
     *
     * @param roster the roster of the network
     */
    EventDeduplicator(@NonNull final RosterWrapper roster) {
        this.roster = requireNonNull(roster);
    }

    /**
     * Checks whether an event has already been submitted by the node that is submitting it now, recording the
     * submission as observed if it has not.
     *
     * <p>Events that are expired for every node in the network, or that the map has already been shifted past, are
     * reported as unique without being recorded. The map no longer tracks birth rounds that old, so there is no way to
     * tell whether such an event has been seen before.
     *
     * @param event     the event to check
     * @param submitter the rank of the node submitting the event
     * @return the tracked event if the submission is unique and was recorded, or is too old to be checked, and
     *     {@code null} if the node has submitted the event before
     */
    @Nullable
    TrackedEvent addIfUnique(@NonNull final PlatformEvent event, final int submitter) {
        if (isUntracked(event)) {
            return newTrackedEvent(event);
        }

        final List<TrackedEvent> trackedEvents = observedEvents.computeIfAbsent(event.getDescriptor(), NEW_LIST);
        if (trackedEvents == null) {
            // Not expected: the check above rules out every birth round outside the window of the map
            return newTrackedEvent(event);
        }

        final Bytes signature = event.getSignature();
        TrackedEvent tracked = null;
        for (final TrackedEvent candidate : trackedEvents) {
            if (candidate.signature().equals(signature)) {
                tracked = candidate;
                break;
            }
        }
        if (tracked == null) {
            tracked = newTrackedEvent(event);
            trackedEvents.add(tracked);
        }
        if (tracked.submitters().get(submitter)) {
            return null;
        }
        tracked.submitters().set(submitter);
        return tracked;
    }

    /**
     * Finds an event that has been observed.
     *
     * @param event an instance of the event
     * @return the tracked event, or {@code null} if it has not been observed or is too old to be remembered
     */
    @Nullable
    TrackedEvent find(@NonNull final PlatformEvent event) {
        if (isUntracked(event)) {
            return null;
        }
        final List<TrackedEvent> trackedEvents = observedEvents.get(event.getDescriptor());
        if (trackedEvents == null) {
            return null;
        }
        final Bytes signature = event.getSignature();
        for (final TrackedEvent candidate : trackedEvents) {
            if (candidate.signature().equals(signature)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Reports whether an event is too old to be tracked: it is expired for every node in the network, or its birth round
     * is below the window of {@link #observedEvents}. The two differ when the oldest event window has moved backwards,
     * since the window of the map never does.
     *
     * @param event the event
     * @return {@code true} if the event is not tracked
     */
    private boolean isUntracked(@NonNull final PlatformEvent event) {
        final long threshold = Math.max(oldestEventWindow.expiredThreshold(), shiftedThroughBirthRound);
        return event.getBirthRound() < threshold;
    }

    @NonNull
    private TrackedEvent newTrackedEvent(@NonNull final PlatformEvent event) {
        return new TrackedEvent(event, roster.index(event.getCreatorId()), roster.size());
    }

    /**
     * Reports the event window of the node that is furthest behind. Descriptors with a birth round that is expired for
     * every node are forgotten, since no node will submit or accept them again.
     *
     * @param eventWindow the oldest event window in the network
     */
    public void setOldestEventWindow(@NonNull final EventWindow eventWindow) {
        oldestEventWindow = requireNonNull(eventWindow);

        final long expiredThreshold = eventWindow.expiredThreshold();
        if (expiredThreshold > shiftedThroughBirthRound) {
            observedEvents.shiftWindow(expiredThreshold);
            shiftedThroughBirthRound = expiredThreshold;
        }
    }

    /**
     * Forgets every event that has been observed.
     */
    public void clear() {
        observedEvents.clear();
    }
}
