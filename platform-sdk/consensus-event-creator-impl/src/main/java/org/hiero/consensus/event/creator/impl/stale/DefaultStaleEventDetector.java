// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.stale;

import com.swirlds.component.framework.transformers.RoutableData;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.event.StaleEventDetectorOutput;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.sequence.map.StandardSequenceMap;

/**
 * Detects when a self event becomes stale. Note that this detection may not observe a self event go stale if the node
 * needs to reconnect or restart.
 */
public class DefaultStaleEventDetector implements StaleEventDetector {

    /**
     * The ID of this node.
     */
    private final NodeId selfId;

    /**
     * Self events that have not yet reached consensus.
     */
    private final StandardSequenceMap<EventDescriptorWrapper, PlatformEvent> selfEvents;

    /**
     * The most recent event window we know about.
     */
    private EventWindow currentEventWindow;

    /**
     * Metrics for the stale event detector.
     */
    private final StaleEventDetectorMetrics metrics;

    /**
     * Constructor.
     *
     * @param metrics       the {@link Metrics} system
     * @param selfId        the ID of this node
     */
    public DefaultStaleEventDetector(@NonNull final Metrics metrics, @NonNull final NodeId selfId) {
        this.selfId = Objects.requireNonNull(selfId);
        this.selfEvents = new StandardSequenceMap<>(0, 1024, true, EventDescriptorWrapper::birthRound);
        this.metrics = new StaleEventDetectorMetrics(metrics);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public List<RoutableData<StaleEventDetectorOutput>> addSelfEvent(@NonNull final PlatformEvent event) {
        if (currentEventWindow == null) {
            throw new IllegalStateException("Event window must be set before adding self events");
        }

        final RoutableData<StaleEventDetectorOutput> selfEvent =
                new RoutableData<>(StaleEventDetectorOutput.SELF_EVENT, event);

        if (currentEventWindow.isAncient(event)) {
            // Although unlikely, it is plausible for an event to go stale before it is added to the detector.
            handleStaleEvent(event);

            final RoutableData<StaleEventDetectorOutput> staleEvent =
                    new RoutableData<>(StaleEventDetectorOutput.STALE_SELF_EVENT, event);
            return List.of(selfEvent, staleEvent);
        }

        selfEvents.put(event.getDescriptor(), event);
        return List.of(selfEvent);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public List<RoutableData<StaleEventDetectorOutput>> addConsensusRound(
            @NonNull final ConsensusRound consensusRound) {
        for (final PlatformEvent event : consensusRound.getConsensusEvents()) {
            if (event.getCreatorId().equals(selfId)) {
                selfEvents.remove(event.getDescriptor());
            }
        }

        final List<PlatformEvent> staleEvents = new ArrayList<>();
        currentEventWindow = consensusRound.getEventWindow();
        selfEvents.shiftWindow(currentEventWindow.ancientThreshold(), (descriptor, event) -> staleEvents.add(event));

        final List<RoutableData<StaleEventDetectorOutput>> output = new ArrayList<>(staleEvents.size());
        for (final PlatformEvent event : staleEvents) {
            handleStaleEvent(event);
            output.add(new RoutableData<>(StaleEventDetectorOutput.STALE_SELF_EVENT, event));
        }

        return output;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setInitialEventWindow(@NonNull final EventWindow initialEventWindow) {
        this.currentEventWindow = Objects.requireNonNull(initialEventWindow);
        selfEvents.shiftWindow(currentEventWindow.ancientThreshold());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        selfEvents.clear();
        currentEventWindow = null;
    }

    /**
     * Handle a stale event.
     *
     * @param event the stale event
     */
    private void handleStaleEvent(@NonNull final PlatformEvent event) {
        metrics.reportStaleEvent(event);
    }
}
