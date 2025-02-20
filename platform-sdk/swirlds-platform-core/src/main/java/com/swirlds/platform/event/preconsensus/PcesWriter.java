// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import com.swirlds.component.framework.component.InputWireLabel;
import com.swirlds.platform.consensus.EventWindow;
import com.swirlds.platform.event.PlatformEvent;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * This object is responsible for writing preconsensus events to disk.
 */
public interface PcesWriter {

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
     * @return the sequence number of the last event durably written to the stream, or null if this method call didn't
     * result in any additional events being durably written to the stream
     */
    @InputWireLabel("events to write")
    @Nullable
    Long writeEvent(@NonNull PlatformEvent event);

    /**
     * Inform the preconsensus event writer that a discontinuity has occurred in the preconsensus event stream.
     *
     * @param newOriginRound the round of the state that the new stream will be starting from
     * @return the sequence number of the last event durably written to the stream, or null if this method call didn't
     * result in any additional events being durably written to the stream
     */
    @InputWireLabel("discontinuity")
    @Nullable
    Long registerDiscontinuity(@NonNull Long newOriginRound);

    /**
     * Submit a request to flush a given sequence number to disk as soon as possible. This flush request will be honored
     * as soon as the event with the given sequence number is received.
     *
     * @param sequenceNumber the sequence number to flush
     * @return the sequence number of the last event durably written to the stream, or null if this method call didn't
     * result in any additional events being durably written to the stream
     */
    @InputWireLabel("flush request")
    @Nullable
    Long submitFlushRequest(@NonNull Long sequenceNumber);

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
