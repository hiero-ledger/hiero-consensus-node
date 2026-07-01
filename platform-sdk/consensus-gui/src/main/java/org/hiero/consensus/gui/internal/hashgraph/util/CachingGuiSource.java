// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gui.internal.hashgraph.util;

import com.hedera.hapi.node.state.roster.Roster;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.gui.internal.GuiEventStorage;
import org.hiero.consensus.gui.internal.hashgraph.HashgraphGuiConstants;
import org.hiero.consensus.gui.internal.hashgraph.HashgraphGuiSource;
import org.hiero.consensus.hashgraph.impl.EventImpl;
import org.hiero.consensus.model.event.EventConstants;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * A {@link HashgraphGuiSource} that wraps another source but caches the results until {@link #refresh()} is called
 */
public class CachingGuiSource implements HashgraphGuiSource {
    private final HashgraphGuiSource source;
    private List<EventImpl> events = null;
    private Roster roster = null;
    private final GuiEventStorage eventStorage;
    private long maxSequenceNumber = EventConstants.GENERATION_UNDEFINED;
    private long startSequenceNum = 1;
    private int numEvents = HashgraphGuiConstants.DEFAULT_NUM_EVENTS_TO_DISPLAY;

    public CachingGuiSource(final HashgraphGuiSource source) {
        this.source = source;
        this.eventStorage = source.getEventStorage();
    }

    @Override
    public long getMaxSequenceNumber() {
        return maxSequenceNumber;
    }

    @Override
    @NonNull
    public List<EventImpl> getEvents(final long startSequenceNum, final int numEvents) {
        this.startSequenceNum = startSequenceNum;
        this.numEvents = numEvents;
        return events;
    }

    @Override
    @NonNull
    public Roster getRoster() {
        return roster;
    }

    @Override
    public boolean isReady() {
        return events != null && roster != null && maxSequenceNumber != PlatformEvent.UNASSIGNED_SEQUENCE_NUMBER;
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
            events = source.getEvents(startSequenceNum, numEvents);
            roster = source.getRoster();
            maxSequenceNumber = source.getMaxSequenceNumber();
        }
    }
}
