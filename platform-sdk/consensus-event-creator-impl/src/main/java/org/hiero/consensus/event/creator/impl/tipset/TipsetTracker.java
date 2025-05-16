// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.tipset;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static org.hiero.consensus.event.creator.impl.tipset.Tipset.merge;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.common.utility.throttle.RateLimitedLogger;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.sequence.map.SequenceMap;
import org.hiero.consensus.model.sequence.map.StandardSequenceMap;

/**
 * Computes and tracks tipsets for non-ancient events.
 */
public class TipsetTracker {

    private static final Logger logger = LogManager.getLogger(TipsetTracker.class);

    private static final int INITIAL_TIPSET_MAP_CAPACITY = 64;

    /**
     * Tipsets for all non-ancient events we know about.
     */
    private final SequenceMap<EventDescriptorWrapper, Tipset> tipsets;

    /**
     * This tipset is equivalent to a tipset that would be created by merging all tipsets of all events that this object
     * has ever observed. If you ask this tipset for the generation for a particular node, it will return the highest
     * generation of all events we have ever received from that node.
     */
    private Tipset latestGenerations;

    private final Roster roster;

    private final AncientMode ancientMode;
    private EventWindow eventWindow;
    private final NodeId selfId;

    private final RateLimitedLogger ancientEventLogger;

    /**
     * Create a new tipset tracker.
     *
     * @param time        provides wall clock time
     * @param selfId      the id of this node
     * @param roster      the current roster
     * @param ancientMode the {@link AncientMode} to use
     */
    public TipsetTracker(
            @NonNull final Time time,
            @NonNull final NodeId selfId,
            @NonNull final Roster roster,
            @NonNull final AncientMode ancientMode) {

        this.roster = Objects.requireNonNull(roster);
        this.selfId = Objects.requireNonNull(selfId);
        this.latestGenerations = new Tipset(roster);

        tipsets = new StandardSequenceMap<>(0, INITIAL_TIPSET_MAP_CAPACITY, true, ancientMode::selectIndicator);

        ancientEventLogger = new RateLimitedLogger(logger, time, Duration.ofMinutes(1));

        this.ancientMode = Objects.requireNonNull(ancientMode);
        this.eventWindow = EventWindow.getGenesisEventWindow(ancientMode);
    }

    /**
     * Set the event window.
     *
     * @param eventWindow the current event window
     */
    public void setEventWindow(@NonNull final EventWindow eventWindow) {
        this.eventWindow = Objects.requireNonNull(eventWindow);
        tipsets.shiftWindow(eventWindow.ancientThreshold());
    }

    /**
     * Get the current event window (from this class's perspective).
     *
     * @return the event window
     */
    @NonNull
    public EventWindow getEventWindow() {
        return eventWindow;
    }

    /**
     * Add a new self event to the tracker. We track the tipset for all events, including self events, but since self
     * advancement never counts toward the advancement score, the latest self generation is never updated.
     *
     * @param selfEventDesc the descriptor of the self event being added
     * @param parents       the parent descriptors of the self event being added
     * @return the tipset for the new self event
     */
    @NonNull
    public Tipset addSelfEvent(
            @NonNull final EventDescriptorWrapper selfEventDesc, @NonNull final List<EventDescriptorWrapper> parents) {
        logIfNotSelfEvent(selfEventDesc);
        logIfAncient(selfEventDesc);

        final List<Tipset> parentTipsets = getParentTipsets(parents);

        // Do not advance the self generation in the tipset for two reasons:
        // 1. Self advancement does not contribute to the advancement score
        // 2. We just created this event, and it does not yet have a generation to use because it
        // will be assigned by the orphan buffer later. Furthermore, we do not want to assign it
        // here because the orphan buffer might disagree about the value given that event windows
        // are process asynchronously.
        final Tipset eventTipset;
        if (parentTipsets.isEmpty()) {
            eventTipset = new Tipset(roster);
        } else {
            eventTipset = merge(parentTipsets);
        }

        tipsets.put(selfEventDesc, eventTipset);

        return eventTipset;
    }

    /**
     * Add a new event, not created by this node, to the tracker.
     *
     * @param event the peer event to add
     * @return the tipset for the event that was added
     */
    @NonNull
    public Tipset addPeerEvent(@NonNull final PlatformEvent event) {
        logIfSelfEvent(event.getDescriptor());
        logIfAncient(event.getDescriptor());

        final List<Tipset> parentTipsets = getParentTipsets(event.getAllParents());

        final Tipset eventTipset;
        if (parentTipsets.isEmpty()) {
            eventTipset = new Tipset(roster).advance(event.getCreatorId(), event.getNGen());
        } else {
            eventTipset = merge(parentTipsets).advance(event.getCreatorId(), event.getNGen());
        }

        tipsets.put(event.getDescriptor(), eventTipset);
        latestGenerations = latestGenerations.advance(event.getCreatorId(), event.getNGen());

        return eventTipset;
    }

    private void logIfNotSelfEvent(@NonNull final EventDescriptorWrapper descriptor) {
        if (!selfId.equals(descriptor.creator())) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "Attempt to add peer event as self event to the TipsetTracker. Self Id: {}, Event Creator: {}",
                    selfId.id(),
                    descriptor.creator());
        }
    }

    private void logIfSelfEvent(@NonNull final EventDescriptorWrapper descriptor) {
        if (selfId.equals(descriptor.creator())) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "Attempt to add self event as peer event to the TipsetTracker. Self Id: {}, Event Creator: {}",
                    selfId.id(),
                    descriptor.creator());
        }
    }

    @NonNull
    private List<Tipset> getParentTipsets(@NonNull final List<EventDescriptorWrapper> parents) {
        return parents.stream().map(tipsets::get).filter(Objects::nonNull).toList();
    }

    private void logIfAncient(@NonNull final EventDescriptorWrapper eventDescriptorWrapper) {
        if (eventWindow.isAncient(eventDescriptorWrapper)) {
            // Note: although we don't immediately return from this method, the tipsets.put()
            // will not update the data structure for an ancient event. We should never
            // enter this bock of code. This log is here as a canary to alert us if we somehow do.
            ancientEventLogger.error(
                    EXCEPTION.getMarker(),
                    "Rejecting ancient event from {} with threshold {}. Current event window is {}",
                    eventDescriptorWrapper.creator(),
                    ancientMode.selectIndicator(eventDescriptorWrapper),
                    eventWindow);
        }
    }

    /**
     * Get the tipset of an event, or null if the event is not being tracked.
     *
     * @param eventDescriptorWrapper the fingerprint of the event
     * @return the tipset of the event, or null if the event is not being tracked
     */
    @Nullable
    public Tipset getTipset(@NonNull final EventDescriptorWrapper eventDescriptorWrapper) {
        return tipsets.get(eventDescriptorWrapper);
    }

    /**
     * Get the highest generation of all events we have received from a particular node.
     *
     * @param nodeId the node in question
     * @return the highest generation of all events received by a given node
     */
    public long getLatestGenerationForNode(@NonNull final NodeId nodeId) {
        return latestGenerations.getTipGenerationForNode(nodeId);
    }

    /**
     * Get number of tipsets being tracked.
     */
    public int size() {
        return tipsets.getSize();
    }

    /**
     * Reset the tipset tracker to its initial state.
     */
    public void clear() {
        eventWindow = EventWindow.getGenesisEventWindow(ancientMode);
        latestGenerations = new Tipset(roster);
        tipsets.clear();
    }
}
