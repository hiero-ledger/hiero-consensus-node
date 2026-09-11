// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.intake.impl.validation;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.wiring.framework.component.InputWireLabel;

/**
 * Validates that events are internally complete and consistent.
 */
public interface InternalEventValidator {
    /**
     * Validate the internal data integrity of an event.
     * <p>
     * If the event is determined to be valid, it is returned.
     *
     * @param event the event to validate
     * @return the event if it is valid, otherwise null
     */
    @InputWireLabel("non-validated events")
    @Nullable
    PlatformEvent validateEvent(@NonNull PlatformEvent event);
}
