// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event;

import com.swirlds.component.framework.component.InputWireLabel;
import com.swirlds.platform.wiring.NoInput;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;

/**
 * Buffers events from the future (i.e. events with a birth round that is greater than the round that consensus is
 * currently working on). It is important to note that the future event buffer is only used to store events from the
 * near future that can be fully validated. Events from beyond the event horizon (i.e. far future events that cannot be
 * immediately validated) are never stored by any part of the system.
 * <p>
 * Output from the future event buffer is guaranteed to preserve topological ordering, as long as the input to the
 * buffer is topologically ordered.
 */
public interface FutureEventBuffer {
    /**
     * Add an event to the future event buffer.
     *
     * @param event the event to add
     * @return a list containing the event if it is not a time traveler, or null if the event is from the future and
     * needs to be buffered.
     */
    @InputWireLabel("preconsensus event")
    @Nullable
    List<PlatformEvent> addEvent(@NonNull PlatformEvent event);

    /**
     * Update the current event window. As the event window advances, time catches up to time travelers, and events that
     * were previously from the future are now from the present.
     *
     * @param eventWindow the new event window
     * @return a list of events that were previously from the future but are now from the present
     */
    @InputWireLabel("event window")
    @Nullable
    List<PlatformEvent> updateEventWindow(@NonNull EventWindow eventWindow);

    /**
     * Clear all data from the future event buffer.
     *
     * @param ignored ignored trigger object
     */
    @InputWireLabel("clear")
    void clear(@NonNull NoInput ignored);
}
