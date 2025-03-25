// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.orphan;

import com.swirlds.component.framework.component.InputWireLabel;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;

/**
 * Takes as input an unordered stream of {@link PlatformEvent}s and emits a stream
 * of {@link PlatformEvent}s in topological order.
 */
public interface OrphanBuffer {

    /**
     * Add a new event to the buffer if it is an orphan.
     * <p>
     * Events that are ancient are ignored, and events that don't have any missing parents are immediately passed along
     * down the pipeline.
     *
     * @param event the event to handle
     * @return the list of events that are no longer orphans as a result of this event being handled
     */
    @InputWireLabel("unordered events")
    @NonNull
    List<PlatformEvent> handleEvent(@NonNull PlatformEvent event);

    /**
     * Sets the event window that defines when an event is considered ancient.
     *
     * @param eventWindow the event window
     * @return the list of events that are no longer orphans as a result of this change
     */
    @InputWireLabel("event window")
    @NonNull
    List<PlatformEvent> setEventWindow(@NonNull final EventWindow eventWindow);

    /**
     * Clears the orphan buffer.
     */
    void clear();
}
