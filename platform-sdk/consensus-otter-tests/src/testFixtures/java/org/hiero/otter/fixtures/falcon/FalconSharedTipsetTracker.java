// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import org.hiero.consensus.event.creator.impl.tipset.DefaultTipsetTracker;
import org.hiero.consensus.event.creator.impl.tipset.Tipset;
import org.hiero.consensus.event.creator.impl.tipset.TipsetTracker;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.consensus.model.sequence.map.SequenceMap;
import org.hiero.consensus.model.sequence.map.StandardSequenceMap;

/**
 * The {@link TipsetTracker} of a mini node, which uses the tipsets that {@link FalconSharedTipsets} shares between all
 * mini nodes of a network. It returns the tipsets a {@link DefaultTipsetTracker} of the node would return, apart from
 * the ordering keys, which are the creation times of the events, and the node's own entry, which a tracker never uses.
 *
 * <p>A tracker has a parent of an event it adds if and only if the parent is not ancient for it: the intake releases an
 * event only after its non-ancient parents, and the future event buffer of the event creation manager keeps that order,
 * because an event never has a lower birth round than its parents. The shared tipset of a peer event is therefore what
 * this tracker would compute, unless
 * <ul>
 *     <li>a parent is not ancient for this tracker but was not included in the shared tipset, or the other way round,
 *     which happens when trackers have different event windows, or</li>
 *     <li>this tracker's tipset of a parent differs from the shared one.</li>
 * </ul>
 * In both cases, this tracker computes the tipset itself, and keeps it if it differs from the shared one.
 *
 * <p>The tracker also keeps the tipsets of the node's own events, because the node does not advance its own entry for
 * them, and the tipsets of peer events that the shared tipsets cannot store.
 *
 * <p>{@link #getTipset(EventDescriptorWrapper)} may return the tipset of a peer event this tracker has not added yet,
 * but it is only asked for events the tracker has added. This class is not thread-safe.
 */
@SuppressWarnings("ForLoopReplaceableByForEach")
final class FalconSharedTipsetTracker implements TipsetTracker {

    /** The initial capacity of the tracker's own tipset maps, in birth rounds. */
    private static final int INITIAL_CAPACITY = 64;

    /** The tipsets shared with the trackers of the other mini nodes. */
    private final FalconSharedTipsets sharedTipsets;

    /** The id of this node. */
    private final NodeId selfId;

    /** The roster. */
    private final RosterWrapper roster;

    /** The tipsets of this node's own events. */
    private final SequenceMap<EventDescriptorWrapper, Tipset> ownTipsets =
            new StandardSequenceMap<>(0, INITIAL_CAPACITY, true, EventDescriptorWrapper::birthRound);

    /** The tipsets of peer events that differ from the shared ones, or that the shared tipsets cannot store. */
    private final SequenceMap<EventDescriptorWrapper, Tipset> peerTipsets =
            new StandardSequenceMap<>(0, INITIAL_CAPACITY, true, EventDescriptorWrapper::birthRound);

    /** The parents' tipsets of the event being added. Reused across calls. */
    private final List<Tipset> parentTipsets = new ArrayList<>();

    /** A tipset holding the ordering key of the latest event this tracker has added from each node. */
    private Tipset latestTips;

    /** The current event window. */
    private EventWindow eventWindow = EventWindow.getGenesisEventWindow();

    /** The number of peer events whose tipset differed from the shared one. */
    private long divergedTipsetCount;

    /**
     * Creates a tracker. Use {@link FalconSharedTipsets#newTracker(NodeId, RosterWrapper)}, which registers it.
     *
     * @param sharedTipsets the tipsets shared with the trackers of the other mini nodes
     * @param selfId the id of this node
     * @param roster the roster
     */
    FalconSharedTipsetTracker(
            @NonNull final FalconSharedTipsets sharedTipsets,
            @NonNull final NodeId selfId,
            @NonNull final RosterWrapper roster) {
        this.sharedTipsets = requireNonNull(sharedTipsets);
        this.selfId = requireNonNull(selfId);
        this.roster = requireNonNull(roster);
        this.latestTips = new Tipset(roster);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setEventWindow(@NonNull final EventWindow eventWindow) {
        this.eventWindow = requireNonNull(eventWindow);
        ownTipsets.shiftWindow(eventWindow.ancientThreshold());
        peerTipsets.shiftWindow(eventWindow.ancientThreshold());
        sharedTipsets.pruneAncientTipsets();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public EventWindow getEventWindow() {
        return eventWindow;
    }

    /**
     * {@inheritDoc}
     *
     * <p>As in the {@link DefaultTipsetTracker}, this node's own entry is not advanced.
     */
    @Override
    @NonNull
    public Tipset addSelfEvent(
            @NonNull final EventDescriptorWrapper selfEventDesc, @NonNull final List<EventDescriptorWrapper> parents) {
        if (!selfId.equals(selfEventDesc.creator())) {
            throw new IllegalArgumentException("Event " + selfEventDesc + " is not an event of node " + selfId);
        }
        final Tipset tipset = new Tipset(roster).merge(collectParentTipsets(selfEventDesc, parents));
        ownTipsets.put(selfEventDesc, tipset);
        return tipset;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Tipset addPeerEvent(@NonNull final PlatformEvent event) {
        final EventDescriptorWrapper descriptor = event.getDescriptor();
        if (selfId.equals(descriptor.creator())) {
            throw new IllegalArgumentException("Event " + descriptor + " is an event of node " + selfId);
        }
        final FalconSharedTipsets.Entry shared = sharedTipsets.computeIfAbsent(event);
        final long orderingKey = FalconSharedTipsets.orderingKey(event);

        Tipset tipset = shared.tipset();
        if (!usesSameParents(event.getAllParents(), shared.includedParents())) {
            final Tipset ownTipset = new Tipset(roster)
                    .merge(collectParentTipsets(descriptor, event.getAllParents()))
                    .advance(event.getCreatorId(), orderingKey);
            if (!equalApartFromSelf(ownTipset, shared.tipset())) {
                divergedTipsetCount++;
                tipset = ownTipset;
            }
        }
        if (tipset != shared.tipset() || !sharedTipsets.holds(descriptor)) {
            peerTipsets.put(descriptor, tipset);
        }

        latestTips.advance(event.getCreatorId(), orderingKey);
        return tipset;
    }

    /**
     * Checks whether the shared tipset of an event was computed from the same parents' tipsets as this tracker would
     * use.
     *
     * @param parents the parents of the event
     * @param includedParents the parents included in the shared tipset, see {@link FalconSharedTipsets.Entry}
     * @return {@code true} if this tracker would compute the shared tipset
     */
    private boolean usesSameParents(@NonNull final List<EventDescriptorWrapper> parents, final long includedParents) {
        for (int i = 0, parentsSize = parents.size(); i < parentsSize; i++) {
            final EventDescriptorWrapper parent = parents.get(i);
            final boolean known = !eventWindow.isAncient(parent);
            final boolean included = (includedParents & (1L << i)) != 0;
            if (known != included) {
                return false;
            }
            // Only the tipsets this tracker keeps itself can differ from the shared ones
            if (known
                    && (selfId.equals(parent.creator())
                            || (peerTipsets.getSize() > 0 && peerTipsets.containsKey(parent)))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Collects this tracker's tipsets of the non-ancient parents of an event into {@link #parentTipsets}.
     *
     * @param descriptor the descriptor of the event
     * @param parents the parents of the event
     * @return the tipsets, valid until the next call
     * @throws IllegalStateException if a non-ancient peer parent has no tipset, so the parent was added after the event
     */
    @NonNull
    private List<Tipset> collectParentTipsets(
            @NonNull final EventDescriptorWrapper descriptor, @NonNull final List<EventDescriptorWrapper> parents) {
        parentTipsets.clear();
        for (int i = 0, parentsSize = parents.size(); i < parentsSize; i++) {
            final EventDescriptorWrapper parent = parents.get(i);
            final Tipset tipset = getTipset(parent);
            if (tipset != null) {
                parentTipsets.add(tipset);
            } else if (!eventWindow.isAncient(parent) && !selfId.equals(parent.creator())) {
                throw new IllegalStateException("Parent " + parent + " of " + descriptor
                        + " is not ancient but has no tipset at node " + selfId);
            }
        }
        return parentTipsets;
    }

    /**
     * Checks whether two tipsets are equal in every entry but this node's own.
     *
     * @param a one tipset
     * @param b the other tipset
     * @return {@code true} if the tipsets are equal apart from this node's entry
     */
    private boolean equalApartFromSelf(@NonNull final Tipset a, @NonNull final Tipset b) {
        return !a.getTipAdvancementWeight(selfId, b).isNonZero()
                && !b.getTipAdvancementWeight(selfId, a).isNonZero();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public Tipset getTipset(@NonNull final EventDescriptorWrapper eventDescriptorWrapper) {
        if (eventWindow.isAncient(eventDescriptorWrapper)) {
            return null;
        }
        if (selfId.equals(eventDescriptorWrapper.creator())) {
            return ownTipsets.get(eventDescriptorWrapper);
        }
        if (peerTipsets.getSize() > 0) {
            final Tipset tipset = peerTipsets.get(eventDescriptorWrapper);
            if (tipset != null) {
                return tipset;
            }
        }
        final FalconSharedTipsets.Entry shared = sharedTipsets.get(eventDescriptorWrapper);
        return shared == null ? null : shared.tipset();
    }

    /**
     * {@inheritDoc}
     *
     * <p>This is the latest creation time, in nanoseconds, of the node's events added with
     * {@link #addPeerEvent(PlatformEvent)}.
     */
    @Override
    public long getLatestTipOrderingKeyForNode(@NonNull final NodeId nodeId) {
        return latestTips.getTipOrderingKeyForNode(nodeId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The shared tipsets are not affected, so the tracker can only be used again from a fresh start of the intake.
     */
    @Override
    public void clear() {
        eventWindow = EventWindow.getGenesisEventWindow();
        latestTips = new Tipset(roster);
        ownTipsets.clear();
        peerTipsets.clear();
    }

    /**
     * Gets the number of peer events whose tipset differed from the shared one. Exposed for testing.
     *
     * @return the number of such events
     */
    long getDivergedTipsetCount() {
        return divergedTipsetCount;
    }
}
