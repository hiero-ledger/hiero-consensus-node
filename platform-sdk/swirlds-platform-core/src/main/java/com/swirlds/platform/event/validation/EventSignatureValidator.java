// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.validation;

import com.swirlds.component.framework.component.InputWireLabel;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;

/**
 * Verifies event signatures
 */
public interface EventSignatureValidator {

    /**
     * Validate event signature
     *
     * @param event the event to verify the signature of
     * @return the event if the signature is valid, otherwise null
     */
    @InputWireLabel("PlatformEvent")
    @Nullable
    PlatformEvent validateSignature(@NonNull final PlatformEvent event);

    /**
     * Set the event window that defines the minimum threshold required for an event to be non-ancient
     *
     * @param eventWindow the event window
     */
    @InputWireLabel("event window")
    void setEventWindow(@NonNull final EventWindow eventWindow);

    /**
     * Set the previous and current rosters
     *
     * @param rosterUpdate the new rosters
     */
    @InputWireLabel("RosterUpdate")
    void updateRosters(@NonNull final RosterUpdate rosterUpdate);
}
