// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gui.internal.hashgraph;

import com.hedera.hapi.node.state.roster.Roster;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.gui.internal.GuiEventStorage;
import org.hiero.consensus.hashgraph.impl.EventImpl;

/**
 * Provides the {@code HashgraphGui} information it needs to render an image of the hashgraph
 */
public interface HashgraphGuiSource {

    /**
     * @return the maximum sequence number of all events this source has
     */
    long getMaxSequenceNumber();

    /**
     * Get events to be displayed by the GUI
     *
     * @param startSequenceNum the start sequence number of events returned
     * @param numEvents  the number of events to be returned
     * @return a list of requested events
     */
    @NonNull
    List<EventImpl> getEvents(final long startSequenceNum, final int numEvents);

    @NonNull
    Roster getRoster();

    /**
     * @return true if the source is ready to return data
     */
    boolean isReady();

    /**
     * Get the event storage used by the GUI.
     *
     * @return the event storage
     */
    GuiEventStorage getEventStorage();
}
