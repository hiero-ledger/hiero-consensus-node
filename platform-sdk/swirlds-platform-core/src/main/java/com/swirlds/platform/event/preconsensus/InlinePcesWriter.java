// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import com.swirlds.component.framework.component.InputWireLabel;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;

/**
 * This object is responsible for writing preconsensus events to disk. It
 * writes events to disk and then outputs them once it ensures they are durable.
 */
public interface InlinePcesWriter {

    /**
     * Prior to this method being called, all events added to the preconsensus event stream are assumed to be events
     * read from the preconsensus event stream on disk. The events from the stream on disk are not re-written to the
     * disk, and are considered to be durable immediately upon ingest.
     */
    @InputWireLabel("done streaming pces")
    void beginStreamingNewEvents();

    /**
     * Write an event to the stream.
     *
     * @param event the event to be written
     * @return the event written
     */
    @InputWireLabel("events to write")
    @NonNull
    PlatformEvent writeEvent(@NonNull PlatformEvent event);

    /**
     * Inform the preconsensus event writer that a discontinuity has occurred in the preconsensus event stream.
     *
     * @param newOriginRound the round of the state that the new stream will be starting from
     */
    @InputWireLabel("discontinuity")
    void registerDiscontinuity(@NonNull Long newOriginRound);

    /**
     * Let the event writer know the current non-ancient event boundary. Ancient events will be ignored if added to the
     * event writer.
     *
     * @param nonAncientBoundary describes the boundary between ancient and non-ancient events
     */
    @InputWireLabel("event window")
    void updateNonAncientEventBoundary(@NonNull EventWindow nonAncientBoundary);

    /**
     * Set the minimum ancient indicator needed to be kept on disk.
     *
     * @param minimumAncientIdentifierToStore the minimum ancient indicator required to be stored on disk
     */
    @InputWireLabel("minimum identifier to store")
    void setMinimumAncientIdentifierToStore(@NonNull Long minimumAncientIdentifierToStore);
}
