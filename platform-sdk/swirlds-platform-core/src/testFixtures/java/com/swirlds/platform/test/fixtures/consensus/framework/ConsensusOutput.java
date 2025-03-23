// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework;

import com.swirlds.common.utility.Clearable;
import com.swirlds.platform.sequence.set.SequenceSet;
import com.swirlds.platform.sequence.set.StandardSequenceSet;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;

/**
 * Stores all output of consensus used in testing. This output can be used to validate consensus results.
 */
public class ConsensusOutput implements Clearable {
    private final AncientMode ancientMode;
    private final LinkedList<ConsensusRound> consensusRounds;
    private final LinkedList<PlatformEvent> addedEvents;
    private final LinkedList<PlatformEvent> staleEvents;

    private final SequenceSet<PlatformEvent> nonAncientEvents;
    private final SequenceSet<EventDescriptorWrapper> nonAncientConsensusEvents;

    private long latestRound;

    private EventWindow eventWindow;

    /**
     * Creates a new instance.
     *
     * @param ancientMode the ancient mode
     */
    public ConsensusOutput(@NonNull final AncientMode ancientMode) {
        this.ancientMode = ancientMode;
        addedEvents = new LinkedList<>();
        consensusRounds = new LinkedList<>();
        staleEvents = new LinkedList<>();

        nonAncientEvents = new StandardSequenceSet<>(
                0, 1024, true, e -> ancientMode.selectIndicator(e.getGeneration(), e.getBirthRound()));
        nonAncientConsensusEvents = new StandardSequenceSet<>(
                0,
                1024,
                true,
                ed -> ancientMode.selectIndicator(
                        ed.eventDescriptor().generation(), ed.eventDescriptor().birthRound()));
        eventWindow = EventWindow.getGenesisEventWindow(ancientMode);
    }

    public void eventAdded(@NonNull final PlatformEvent event) {
        addedEvents.add(event);
        nonAncientEvents.add(event);
    }

    public void consensusRound(@NonNull final ConsensusRound consensusRound) {
        consensusRounds.add(consensusRound);

        // Look for stale events
        for (final PlatformEvent consensusEvent : consensusRound.getConsensusEvents()) {
            nonAncientConsensusEvents.add(consensusEvent.getDescriptor());
        }
        final long ancientThreshold = consensusRound.getEventWindow().getAncientThreshold();
        nonAncientEvents.shiftWindow(ancientThreshold, e -> {
            if (!nonAncientConsensusEvents.contains(e.getDescriptor())) {
                staleEvents.add(e);
            }
        });
        nonAncientConsensusEvents.shiftWindow(ancientThreshold);

        eventWindow = consensusRound.getEventWindow();
    }

    /**
     * @return a queue of all events that have been marked as stale
     */
    public @NonNull LinkedList<PlatformEvent> getStaleEvents() {
        return staleEvents;
    }

    /**
     * @return a queue of all rounds that have reached consensus
     */
    public @NonNull LinkedList<ConsensusRound> getConsensusRounds() {
        return consensusRounds;
    }

    public @NonNull LinkedList<PlatformEvent> getAddedEvents() {
        return addedEvents;
    }

    public @NonNull List<PlatformEvent> sortedAddedEvents() {
        final List<PlatformEvent> sortedEvents = new ArrayList<>(addedEvents);
        sortedEvents.sort(Comparator.comparingLong(PlatformEvent::getGeneration)
                .thenComparingLong(e -> e.getCreatorId().id())
                .thenComparing(PlatformEvent::getHash));
        return sortedEvents;
    }

    /**
     * Get the latest round that reached consensus.
     *
     * @return the latest round that reached consensus
     */
    public long getLatestRound() {
        return latestRound;
    }

    /**
     * Get the current event window.
     * @return the current event window
     */
    @NonNull
    public EventWindow getEventWindow() {
        return eventWindow;
    }

    @Override
    public void clear() {
        addedEvents.clear();
        consensusRounds.clear();
        staleEvents.clear();
        nonAncientEvents.clear();
        nonAncientConsensusEvents.clear();
        latestRound = 0;
        eventWindow = EventWindow.getGenesisEventWindow(AncientMode.GENERATION_THRESHOLD);
    }
}
