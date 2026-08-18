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
import org.hiero.consensus.model.sequence.map.ConcurrentSequenceMap;
import org.hiero.consensus.model.sequence.map.SequenceMap;

/**
 * Recognizes events that have already been submitted to the simulated network.
 *
 * <p>A node re-offers events to gossip after they have been through its intake pipeline, so the same event is submitted
 * once by the node that created it and again by every node that receives it. Only the first submission needs to be
 * added to the event log; the rest are duplicates.
 *
 * <p>Uniqueness is defined the same way as it is in the production deduplicator: an event is a duplicate only when both
 * its descriptor - which carries the event's hash - and its signature have already been observed together. A descriptor
 * seen with a signature that has not accompanied it before is a distinct event rather than a duplicate.
 *
 * <p>This class is thread safe.
 */
public class EventDeduplicator {

    /**
     * Avoid the creation of lambdas for {@link SequenceMap#computeIfAbsent} by reusing this lambda.
     */
    private static final Function<EventDescriptorWrapper, Set<Bytes>> NEW_HASH_SET = ignored -> new HashSet<>();

    /**
     * The number of birth rounds tracked at once. The map expands beyond this as birth rounds climb.
     */
    private static final int INITIAL_CAPACITY = 1024;

    /**
     * A map from event descriptor to the set of signatures that have been observed alongside that descriptor.
     */
    private final SequenceMap<EventDescriptorWrapper, Set<Bytes>> observedEvents = new ConcurrentSequenceMap<>(
            ConsensusConstants.ROUND_FIRST, INITIAL_CAPACITY, true, EventDescriptorWrapper::birthRound);

    /**
     * The event window of the node that is furthest behind, which bounds what the rest of the network still needs.
     */
    private volatile EventWindow oldestEventWindow = EventWindow.getGenesisEventWindow();

    /**
     * The expired threshold that {@link #observedEvents} has been shifted to. The window of a {@link SequenceMap} only
     * moves towards larger values, but the oldest event window in the network can move backwards when a node restarts
     * or when a node is added to a network that is already running, so the two are tracked separately.
     */
    private long shiftedThroughBirthRound = ConsensusConstants.ROUND_FIRST;

    /**
     * Checks whether an event has already been submitted, recording it as observed if it has not.
     *
     * <p>Events that are expired for every node in the network are also reported as duplicates. Such an event is of no
     * use to any node, and the map no longer tracks birth rounds that old, so there is no way to tell whether it has
     * been seen before.
     *
     * @param event the event to check
     * @return {@code true} if the event should not be added to the event log
     */
    public boolean isDuplicate(@NonNull final PlatformEvent event) {
        if (event.getBirthRound() < oldestEventWindow.expiredThreshold()) {
            return true;
        }

        final Set<Bytes> signatures = observedEvents.computeIfAbsent(event.getDescriptor(), NEW_HASH_SET);
        if (signatures == null) {
            // The window shifted past this event's birth round after the check above
            return true;
        }

        return !signatures.add(event.getSignature());
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
