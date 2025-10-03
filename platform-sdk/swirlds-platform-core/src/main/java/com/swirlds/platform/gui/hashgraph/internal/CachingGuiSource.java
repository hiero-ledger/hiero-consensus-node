// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gui.hashgraph.internal;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.platform.gui.GuiEventStorage;
import com.swirlds.platform.gui.hashgraph.HashgraphGuiConstants;
import com.swirlds.platform.gui.hashgraph.HashgraphGuiSource;
import com.swirlds.platform.internal.EventImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.event.EventConstants;

/**
 * A {@link HashgraphGuiSource} that wraps another source but caches the results until {@link #refresh()} is called
 */
public class CachingGuiSource implements HashgraphGuiSource {
    private final HashgraphGuiSource source;
    private List<EventImpl> events = null;
    private Roster roster = null;
    private final GuiEventStorage eventStorage;
    private long maxGeneration = EventConstants.GENERATION_UNDEFINED;
    private long startGeneration = EventConstants.FIRST_GENERATION;
    private int numGenerations = HashgraphGuiConstants.DEFAULT_GENERATIONS_TO_DISPLAY;

    public CachingGuiSource(final HashgraphGuiSource source) {
        this.source = source;
        this.eventStorage = source.getEventStorage();
    }

    @Override
    public long getMaxGeneration() {
        return maxGeneration;
    }

    @Override
    @NonNull
    public List<EventImpl> getEvents(final long startGeneration, final int numGenerations) {
        this.startGeneration = startGeneration;
        this.numGenerations = numGenerations;
        return events;
    }

    @Override
    @NonNull
    public Roster getRoster() {
        return roster;
    }

    @Override
    public boolean isReady() {
        return events != null && roster != null && maxGeneration != EventConstants.GENERATION_UNDEFINED;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public GuiEventStorage getEventStorage() {
        return eventStorage;
    }

    /**
     * Reload the data from the source and cache it
     */
    public void refresh() {
        if (source.isReady()) {
            events = source.getEvents(startGeneration, numGenerations);
            roster = source.getRoster();
            maxGeneration = source.getMaxGeneration();
        }
    }
}
