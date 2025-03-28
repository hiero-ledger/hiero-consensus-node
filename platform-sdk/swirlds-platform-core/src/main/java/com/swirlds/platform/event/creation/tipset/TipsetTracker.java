// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.creation.tipset;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.platform.event.creation.tipset.Tipset.merge;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.common.utility.throttle.RateLimitedLogger;
import com.swirlds.platform.sequence.map.SequenceMap;
import com.swirlds.platform.sequence.map.StandardSequenceMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;

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

    private final RateLimitedLogger ancientEventLogger;

    /**
     * Create a new tipset tracker.
     *
     * @param time        provides wall clock time
     * @param roster      the current roster
     * @param ancientMode the {@link AncientMode} to use
     */
    public TipsetTracker(
            @NonNull final Time time, @NonNull final Roster roster, @NonNull final AncientMode ancientMode) {

        this.roster = Objects.requireNonNull(roster);

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
        tipsets.shiftWindow(eventWindow.getAncientThreshold());
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
     * Add a new event to the tracker.
     *
     * @param eventDescriptorWrapper the descriptor of the event to add
     * @param parents         the parents of the event being added
     * @return the tipset for the event that was added
     */
    @NonNull
    public Tipset addEvent(
            @NonNull final EventDescriptorWrapper eventDescriptorWrapper,
            @NonNull final List<EventDescriptorWrapper> parents) {

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

        final List<Tipset> parentTipsets = new ArrayList<>(parents.size());
        for (final EventDescriptorWrapper parent : parents) {
            final Tipset parentTipset = tipsets.get(parent);
            if (parentTipset != null) {
                parentTipsets.add(parentTipset);
            }
        }

        final Tipset eventTipset;
        if (parentTipsets.isEmpty()) {
            eventTipset = new Tipset(roster)
                    .advance(
                            eventDescriptorWrapper.creator(),
                            eventDescriptorWrapper.eventDescriptor().generation());
        } else {
            eventTipset = merge(parentTipsets)
                    .advance(
                            eventDescriptorWrapper.creator(),
                            eventDescriptorWrapper.eventDescriptor().generation());
        }

        tipsets.put(eventDescriptorWrapper, eventTipset);
        latestGenerations = latestGenerations.advance(
                eventDescriptorWrapper.creator(),
                eventDescriptorWrapper.eventDescriptor().generation());

        return eventTipset;
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
