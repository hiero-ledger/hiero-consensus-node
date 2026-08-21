// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.sequence.map.ConcurrentSequenceMap;
import org.hiero.consensus.model.sequence.map.SequenceMap;

/**
 * Recognizes events that have already been submitted to the simulated network.
 *
 * <p>A node re-offers events to gossip after they have been through its intake pipeline, so the same event is submitted
 * once by the node that created it and again by every node that receives it. Each of those submissions is a distinct
 * offer to the network, because the event log records which node an event is transmitted from and a node can only
 * transmit over its own connections. Collapsing them into one entry would confine an event to the connections of
 * whichever node happened to submit it first, and it would never reach a node that is only reachable by relay.
 *
 * <p>An event is therefore only a duplicate when the <i>same</i> node offers it twice. Uniqueness is defined the same
 * way as it is in the production deduplicator, with the submitting node added: a submission is a duplicate only when
 * the event's descriptor - which carries the event's hash - its signature, and the submitter have all been observed
 * together before. A descriptor seen with a signature that has not accompanied it before is a distinct event rather
 * than a duplicate.
 *
 * <p>This class is not safe to use from more than one thread. The set of submissions held against a descriptor is a
 * plain {@link HashSet}, and {@link SequenceMap#computeIfAbsent} reports a lost insertion race the same way it reports a
 * sequence number that has fallen outside the window, which would be read here as a duplicate. Both callers reach it
 * only from the single-threaded phase of a tick, after the nodes that submit events have stopped running.
 */
public class EventDeduplicator {

    /**
     * Avoid the creation of lambdas for {@link SequenceMap#computeIfAbsent} by reusing this lambda.
     */
    @NonNull
    private static final Function<EventDescriptorWrapper, Set<Submission>> NEW_HASH_SET = ignored -> new HashSet<>();

    /**
     * The number of birth rounds tracked at once. The map expands beyond this as birth rounds climb.
     */
    private static final int INITIAL_CAPACITY = 1024;

    /**
     * A single offer of an event to the network, identified by the node that made it and the signature it carried.
     *
     * @param submitter the node that submitted the event
     * @param signature the signature the event carried
     */
    private record Submission(
            @NonNull NodeId submitter, @NonNull Bytes signature) {}

    /**
     * A map from event descriptor to the submissions that have been observed for that descriptor.
     */
    private final SequenceMap<EventDescriptorWrapper, Set<Submission>> observedEvents = new ConcurrentSequenceMap<>(
            ConsensusConstants.ROUND_FIRST, INITIAL_CAPACITY, true, EventDescriptorWrapper::birthRound);

    /**
     * The event window of the node that is furthest behind, which bounds what the rest of the network still needs.
     */
    @NonNull
    private volatile EventWindow oldestEventWindow = EventWindow.getGenesisEventWindow();

    /**
     * The expired threshold that {@link #observedEvents} has been shifted to. The window of a {@link SequenceMap} only
     * moves towards larger values, but the oldest event window in the network can move backwards when a node restarts
     * or when a node is added to a network that is already running, so the two are tracked separately.
     */
    private long shiftedThroughBirthRound = ConsensusConstants.ROUND_FIRST;

    /**
     * Checks whether an event has already been submitted by the node that is submitting it now, recording the
     * submission as observed if it has not.
     *
     * <p>Events that are expired for every node in the network are also reported as duplicates. Such an event is of no
     * use to any node, and the map no longer tracks birth rounds that old, so there is no way to tell whether it has
     * been seen before.
     *
     * @param event the event to check, with its sender set to the node submitting it
     * @return {@code true} if the event should not be added to the event log
     */
    public boolean addIfUnique(@NonNull final PlatformEvent event) {
        if (event.getBirthRound() < oldestEventWindow.expiredThreshold()) {
            return true;
        }

        final Set<Submission> submissions = observedEvents.computeIfAbsent(event.getDescriptor(), NEW_HASH_SET);
        if (submissions == null) {
            // The window shifted past this event's birth round after the check above
            return true;
        }

        return !submissions.add(new Submission(event.getSenderId(), event.getSignature()));
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
