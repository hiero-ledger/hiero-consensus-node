// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import com.swirlds.component.framework.component.InputWireLabel;
import com.swirlds.platform.event.PlatformEvent;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Responsible for assigning stream sequence numbers to events. All events that are written
 * to the preconsensus event stream must be assigned a sequence number.
 */
public interface PcesSequencer {
    /**
     * Set the stream sequence number of an event.
     *
     * @param event an event that needs a sequence number
     * @return the event with a sequence number set
     */
    @InputWireLabel("unsequenced event")
    @NonNull
    PlatformEvent assignStreamSequenceNumber(@NonNull PlatformEvent event);
}
