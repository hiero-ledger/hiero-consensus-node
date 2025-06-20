// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.stream;

import com.swirlds.common.stream.RunningEventHashOverride;
import com.swirlds.component.framework.component.InputWireLabel;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.event.CesEvent;

/**
 * Generates event stream files when enableEventStreaming is true, and calculates runningHash for consensus Events.
 */
public interface ConsensusEventStream {

    /**
     * Adds a list of events to the event stream.
     *
     * @param events the list of events to add
     */
    @InputWireLabel("consensus events")
    void addEvents(@NonNull final List<CesEvent> events);

    /**
     * Updates the running hash with the given event hash. Called when a state is loaded.
     *
     * @param runningEventHashOverride the hash to update the running hash with
     */
    @InputWireLabel("hash override")
    void legacyHashOverride(@NonNull final RunningEventHashOverride runningEventHashOverride);
}
