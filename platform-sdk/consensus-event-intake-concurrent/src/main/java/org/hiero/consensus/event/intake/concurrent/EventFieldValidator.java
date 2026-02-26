// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.intake.concurrent;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * Validates the internal fields of an event (null checks, byte lengths, transaction limits, etc.).
 *
 * <p>This is a simple functional interface that decouples the {@link EventIntakeProcessor} from
 * any specific validation implementation.
 */
@FunctionalInterface
public interface EventFieldValidator {
    /**
     * Check whether the event's internal fields are valid.
     *
     * @param event the event to validate
     * @return true if the event is valid, false if it should be discarded
     */
    boolean isValid(@NonNull PlatformEvent event);
}
