// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.status.triggers.StatusMachineTrigger;

/**
 * An exception thrown when an illegal {@link StatusMachineTrigger} is received
 */
public class IllegalPlatformStatusException extends RuntimeException {
    /**
     * Constructor
     *
     * @param illegalTrigger the illegal trigger that was received
     * @param status         the status of the platform when the illegal trigger was received
     */
    public IllegalPlatformStatusException(
            @NonNull final StatusMachineTrigger illegalTrigger, @NonNull final PlatformStatus status) {

        super("Received unexpected trigger `%s` with current status of `%s`"
                .formatted(illegalTrigger.getClass().getSimpleName(), status.name()));
    }

    /**
     * String constructor
     *
     * @param message the message
     */
    public IllegalPlatformStatusException(@NonNull final String message) {
        super(message);
    }
}
