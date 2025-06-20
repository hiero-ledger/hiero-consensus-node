// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.validation;

import com.swirlds.component.framework.component.InputWireLabel;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.model.event.PlatformEvent;

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
