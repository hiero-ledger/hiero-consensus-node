// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.gui.internal.hashgraph.util;

import com.hedera.hapi.node.state.roster.Roster;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;
import org.hiero.consensus.hashgraph.impl.EventImpl;
import org.hiero.consensus.hashgraph.impl.test.fixtures.gui.internal.GuiEventStorage;
import org.hiero.consensus.hashgraph.impl.test.fixtures.gui.internal.hashgraph.HashgraphGuiSource;

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
