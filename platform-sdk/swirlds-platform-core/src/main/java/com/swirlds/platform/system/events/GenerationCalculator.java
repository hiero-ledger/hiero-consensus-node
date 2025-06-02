// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.events;

import com.swirlds.component.framework.component.InputWireLabel;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * Performs special migration on events to prepare of the removal of generation.
 */
@FunctionalInterface
public interface GenerationCalculator {
    /**
     * Calculate and set the event's generation if it was created by a version of software that populated generation.
     *
     * @param event the event to calculate generation for
     * @return the event
     */
    @InputWireLabel("PlatformEvent")
    @NonNull
    PlatformEvent maybeCalculateGeneration(@NonNull PlatformEvent event);
}
