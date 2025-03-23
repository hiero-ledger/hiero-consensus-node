// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.creation;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.event.UnsignedEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;

/**
 * An object that creates new events.
 */
public interface EventCreator {

    /**
     * Register a new event from event intake.
     *
     * @param event the event to add
     */
    void registerEvent(@NonNull PlatformEvent event);

    /**
     * Update the event window.
     *
     * @param eventWindow the new event window
     */
    void setEventWindow(@NonNull EventWindow eventWindow);

    /**
     * Create a new event if it is legal to do so. The only time this should not create an event is if there are no
     * eligible parents.
     *
     * @return the new event, or null if it is not legal to create a new event
     */
    @Nullable
    UnsignedEvent maybeCreateEvent();

    /**
     * Reset the event creator to its initial state.
     */
    void clear();
}
