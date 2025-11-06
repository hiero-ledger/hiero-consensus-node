// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gui.hashgraph.internal;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.platform.gui.GuiEventStorage;
import com.swirlds.platform.gui.hashgraph.HashgraphGuiSource;
import com.swirlds.platform.internal.EventImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;

/**
 * A {@link HashgraphGuiSource} that retrieves events from a stream of events
 */
public class StandardGuiSource implements HashgraphGuiSource {

    private final Roster roster;
    private final GuiEventStorage eventStorage;

    /**
     * Constructor
     *
     * @param roster       the current roster
     * @param eventStorage stores information about events
     */
    public StandardGuiSource(@NonNull final Roster roster, @NonNull final GuiEventStorage eventStorage) {
        this.roster = Objects.requireNonNull(roster);
        this.eventStorage = Objects.requireNonNull(eventStorage);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getMaxGeneration() {
        return eventStorage.getMaxGeneration();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<EventImpl> getEvents(final long startGeneration, final int numGenerations) {
        return eventStorage.getNonAncientEvents().stream()
                .filter(e -> e.getNGen() >= startGeneration && e.getNGen() < startGeneration + numGenerations)
                .toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Roster getRoster() {
        return roster;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isReady() {
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public GuiEventStorage getEventStorage() {
        return eventStorage;
    }
}
