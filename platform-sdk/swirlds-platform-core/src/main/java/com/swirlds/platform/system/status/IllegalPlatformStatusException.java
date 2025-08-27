// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.status;

import com.swirlds.platform.system.status.actions.PlatformStatusAction;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * An exception thrown when an illegal {@link PlatformStatusAction} is received
 */
public class IllegalPlatformStatusException extends RuntimeException {
    /**
     * Constructor
     *
     * @param illegalAction the illegal action that was received
     * @param status        the status of the platform when the illegal action was received
     */
    public IllegalPlatformStatusException(
            @NonNull final PlatformStatusAction illegalAction, @NonNull final PlatformStatus status) {

        super("Received unexpected status action `%s` with current status of `%s`"
                .formatted(illegalAction.getClass().getSimpleName(), status.name()));
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
