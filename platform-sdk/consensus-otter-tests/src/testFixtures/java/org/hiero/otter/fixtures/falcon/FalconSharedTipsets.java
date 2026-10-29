// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hiero.consensus.event.creator.impl.tipset.Tipset;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.consensus.model.sequence.map.SequenceMap;
import org.hiero.consensus.model.sequence.map.StandardSequenceMap;

/**
 * The tipsets of events, shared by the {@link FalconSharedTipsetTracker}s of all mini nodes of a network, so that each
 * tipset is computed and stored once rather than once per mini node.
 *
 * <p>A tipset depends only on the hashgraph, apart from a node's own entry, which the node never uses. The ordering key
 * of an event is its creation time in nanoseconds. It is the same for every node, and it increases from self parent to
 * child, because the event creator makes each event's creation time later than its self parent's. The orphan buffer's
 * sequence numbers would not do, because every node numbers the events in the order it receives them.
 *
 * <p>The tipset of an event is computed when the event is first added, from the stored tipsets of its parents, and
 * stored together with the parents it includes. A tracker uses it unless it would compute a different one, see
 * {@link FalconSharedTipsetTracker}. A tipset is kept until its event is ancient for every registered tracker.
 *
 * <p>This class is not thread-safe, and only valid if no node forks.
 */
@SuppressWarnings("ForLoopReplaceableByForEach")
final class FalconSharedTipsets {

    /** The initial capacity of {@link #entries}, in birth rounds. */
    private static final int INITIAL_CAPACITY = 1024;

    /** The number of nanoseconds in a second. */
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    /**
     * A stored tipset.
     *
     * @param tipset the tipset of the event
     * @param includedParents bit {@code i} is set if the tipset of the event's parent at index {@code i} of
     *     {@link PlatformEvent#getAllParents()} was stored when the tipset was computed, and was merged into it
     */
    record Entry(@NonNull Tipset tipset, long includedParents) {}

    /** The stored tipsets, by the descriptors of their events. */
    private final SequenceMap<EventDescriptorWrapper, Entry> entries =
            new StandardSequenceMap<>(0, INITIAL_CAPACITY, true, EventDescriptorWrapper::birthRound);

    /** The trackers that use these tipsets. */
    private final List<FalconSharedTipsetTracker> trackers = new ArrayList<>();

    /** The roster of all trackers, set when the first tracker is created. */
    @Nullable
    private RosterWrapper roster;

    /**
     * Creates a tracker for a node that uses these tipsets.
     *
     * @param selfId the id of the node
     * @param roster the roster, which must be the same for all trackers
     * @return the tracker
     * @throws IllegalArgumentException if the roster differs from the roster of the trackers created before
     */
    @NonNull
    FalconSharedTipsetTracker newTracker(@NonNull final NodeId selfId, @NonNull final RosterWrapper roster) {
        if (this.roster == null) {
            this.roster = requireNonNull(roster);
        } else if (!this.roster.equals(roster)) {
            throw new IllegalArgumentException("All trackers that share tipsets must use the same roster");
        }
        final FalconSharedTipsetTracker tracker = new FalconSharedTipsetTracker(this, selfId, roster);
        trackers.add(tracker);
        return tracker;
    }

    /**
     * Removes a tracker, for example the one of a node that was killed, so that it no longer keeps tipsets.
     *
     * @param tracker the tracker
     */
    void unregister(@NonNull final FalconSharedTipsetTracker tracker) {
        trackers.remove(tracker);
        pruneAncientTipsets();
    }

    /**
     * Forgets the tipsets of the events that are ancient for every tracker. Called when the event window of a tracker
     * changes.
     */
    void pruneAncientTipsets() {
        if (trackers.isEmpty()) {
            return;
        }
        long lowestAncientThreshold = Long.MAX_VALUE;
        for (int i = 0, trackersSize = trackers.size(); i < trackersSize; i++) {
            lowestAncientThreshold = Math.min(
                    lowestAncientThreshold, trackers.get(i).getEventWindow().ancientThreshold());
        }
        // A tracker created late can have a lower threshold than the one the map was shifted to, and the map
        // cannot shift back
        if (lowestAncientThreshold > entries.getFirstSequenceNumberInWindow()) {
            entries.shiftWindow(lowestAncientThreshold);
        }
    }

    /**
     * Checks whether the tipset of an event can be stored, which is not the case if the event is ancient for every
     * tracker that was registered when the tipsets were last pruned.
     *
     * @param descriptor the descriptor of the event
     * @return {@code true} if the tipset of the event can be stored
     */
    boolean holds(@NonNull final EventDescriptorWrapper descriptor) {
        return descriptor.birthRound() >= entries.getFirstSequenceNumberInWindow();
    }

    /**
     * Gets the stored tipset of an event.
     *
     * @param descriptor the descriptor of the event
     * @return the stored tipset, or {@code null} if no tracker has added the event or its tipset has been forgotten
     */
    @Nullable
    Entry get(@NonNull final EventDescriptorWrapper descriptor) {
        return entries.get(descriptor);
    }

    /**
     * Gets the stored tipset of an event, computing it from the stored tipsets of its parents if the event has not
     * been added before. The tipset is not stored if {@link #holds(EventDescriptorWrapper)} is {@code false} for the
     * event.
     *
     * @param event the event
     * @return the tipset of the event
     */
    @NonNull
    Entry computeIfAbsent(@NonNull final PlatformEvent event) {
        final EventDescriptorWrapper descriptor = event.getDescriptor();
        final Entry existing = entries.get(descriptor);
        if (existing != null) {
            return existing;
        }
        final List<EventDescriptorWrapper> parents = event.getAllParents();
        if (parents.size() > Long.SIZE) {
            throw new IllegalArgumentException("Event " + descriptor + " has more than " + Long.SIZE + " parents");
        }
        final List<Tipset> parentTipsets = new ArrayList<>(parents.size());
        long includedParents = 0;
        for (int i = 0, parentsSize = parents.size(); i < parentsSize; i++) {
            final Entry parent = entries.get(parents.get(i));
            if (parent != null) {
                parentTipsets.add(parent.tipset());
                includedParents |= 1L << i;
            }
        }
        final Tipset tipset = new Tipset(requireNonNull(roster, "No tracker has been created"))
                .merge(parentTipsets)
                .advance(event.getCreatorId(), orderingKey(event));
        final Entry entry = new Entry(tipset, includedParents);
        entries.put(descriptor, entry);
        return entry;
    }

    /**
     * Gets the number of stored tipsets. Exposed for testing.
     *
     * @return the number of stored tipsets
     */
    int size() {
        return entries.getSize();
    }

    /**
     * Gets the ordering key of an event, its creation time in nanoseconds since the epoch.
     *
     * @param event the event
     * @return the ordering key
     */
    static long orderingKey(@NonNull final PlatformEvent event) {
        final Instant timeCreated = event.getTimeCreated();
        return timeCreated.getEpochSecond() * NANOS_PER_SECOND + timeCreated.getNano();
    }
}
