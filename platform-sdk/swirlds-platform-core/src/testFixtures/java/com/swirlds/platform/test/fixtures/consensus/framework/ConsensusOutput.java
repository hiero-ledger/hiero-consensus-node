// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework;

import com.swirlds.platform.components.consensus.ConsensusEngineOutput;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.hiero.base.Clearable;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;

/**
 * Stores all output of consensus used in testing. This output can be used to validate consensus results.
 */
public class ConsensusOutput implements Clearable {
    private final LinkedList<ConsensusRound> consensusRounds;
    private final LinkedList<PlatformEvent> preConsensusEvents;
    private final LinkedList<PlatformEvent> addedEvents;
    private final LinkedList<PlatformEvent> staleEvents;

    private long latestRound;

    private EventWindow eventWindow;

    /**
     * Creates a new instance.
     */
    public ConsensusOutput() {
        addedEvents = new LinkedList<>();
        preConsensusEvents = new LinkedList<>();
        consensusRounds = new LinkedList<>();
        staleEvents = new LinkedList<>();

        eventWindow = EventWindow.getGenesisEventWindow();
    }

    public void eventAdded(@NonNull final PlatformEvent event) {
        addedEvents.add(event);
    }

    /**
     * Processes the output of the consensus engine.
     *
     * @param output the output of the consensus engine
     */
    public void consensusEngineOutput(@NonNull final ConsensusEngineOutput output) {
        output.consensusRounds().forEach(this::consensusRound);
        preConsensusEvents.addAll(output.preConsensusEvents());
        staleEvents.addAll(output.staleEvents());
    }

    public void consensusRound(@NonNull final ConsensusRound consensusRound) {
        consensusRounds.add(consensusRound);
        eventWindow = consensusRound.getEventWindow();
    }

    /**
     * @return a queue of all events that have been marked as stale
     */
    public @NonNull LinkedList<PlatformEvent> getStaleEvents() {
        return staleEvents;
    }

    /**
     * Get a set of hashes of all stale events.
     *
     * @return a set of hashes of stale events
     */
    public @NonNull Set<Hash> getStaleEventHashes() {
        return staleEvents.stream().map(PlatformEvent::getHash).collect(Collectors.toSet());
    }

    /**
     * @return a queue of all rounds that have reached consensus
     */
    public @NonNull LinkedList<ConsensusRound> getConsensusRounds() {
        return consensusRounds;
    }

    /**
     * Get the last consensus round if it exists.
     *
     * @return the last consensus round
     * @throws java.util.NoSuchElementException if there are no consensus rounds
     */
    public @NonNull ConsensusRound getLastConsensusRound() {
        return consensusRounds.getLast();
    }

    public @NonNull LinkedList<PlatformEvent> getAddedEvents() {
        return addedEvents;
    }

    public @NonNull List<PlatformEvent> sortedAddedEvents() {
        final List<PlatformEvent> sortedEvents = new ArrayList<>(addedEvents);
        sortedEvents.sort(Comparator.comparingLong(PlatformEvent::getBirthRound)
                .thenComparingLong(e -> e.getCreatorId().id())
                .thenComparing(PlatformEvent::getHash));
        return sortedEvents;
    }

    /**
     * Get the pre-consensus events that have been returned by consensus.
     *
     * @return a list of pre-consensus events
     */
    public @NonNull List<PlatformEvent> getPreConsensusEvents() {
        return preConsensusEvents;
    }

    /**
     * Get the hashes of the pre-consensus events that have been returned by consensus.
     *
     * @return a set of hashes of pre-consensus events
     */
    public @NonNull Set<Hash> getPreConsensusEventHashes() {
        return preConsensusEvents.stream().map(PlatformEvent::getHash).collect(Collectors.toSet());
    }

    /**
     * Get the hashes of the consensus events that have been returned by consensus.
     *
     * @return a set of hashes of consensus events
     */
    public @NonNull Set<Hash> consensusEventHashes() {
        return consensusRounds.stream()
                .map(ConsensusRound::getConsensusEvents)
                .flatMap(List::stream)
                .map(PlatformEvent::getHash)
                .collect(Collectors.toSet());
    }

    /**
     * Get all consensus events that have been returned by consensus.
     *
     * @return a list of consensus events
     */
    public @NonNull List<PlatformEvent> getConsensusEvents() {
        return consensusRounds.stream()
                .map(ConsensusRound::getConsensusEvents)
                .flatMap(List::stream)
                .collect(Collectors.toList());
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
     *
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
        latestRound = 0;
        eventWindow = EventWindow.getGenesisEventWindow();
    }
}
