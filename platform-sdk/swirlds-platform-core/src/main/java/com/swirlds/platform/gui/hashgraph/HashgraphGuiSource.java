// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gui.hashgraph;

import com.swirlds.platform.gui.GuiEventStorage;
import com.swirlds.platform.internal.EventImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.roster.AddressBook;

/**
 * Provides the {@code HashgraphGui} information it needs to render an image of the hashgraph
 */
public interface HashgraphGuiSource {

    /**
     * @return the maximum generation of all events this source has
     */
    long getMaxGeneration();

    /**
     * Get events to be displayed by the GUI
     *
     * @param startGeneration the start generation of events returned
     * @param numGenerations  the number of generations to be returned
     * @return an array of requested events
     */
    @NonNull
    List<EventImpl> getEvents(final long startGeneration, final int numGenerations);

    /**
     * Get the Address Book
     *
     * @return AddressBook
     */
    @NonNull
    AddressBook getAddressBook();

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
