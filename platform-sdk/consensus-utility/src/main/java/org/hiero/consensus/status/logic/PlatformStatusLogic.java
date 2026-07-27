// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.logic;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.status.IllegalPlatformStatusException;
import org.hiero.consensus.status.actions.PlatformStatusAction;

/**
 * Interface representing the state machine logic for an individual {@link PlatformStatus}.
 * <p>
 * {@link #process(PlatformStatusAction)} behaves in the following way:
 * <ul>
 *     <li>If the action results in a status transition, it returns an instance of {@link PlatformStatusLogic}
 *     corresponding to the new status.</li>
 *     <li>If the action does not result in a status transition, it returns a reference to itself, since it will
 *     continue managing the logic for the current status moving forward.</li>
 *     <li>If the action is not valid for the current status, it throws an {@link IllegalPlatformStatusException}.</li>
 * </ul>
 *
 * @see AbstractStatusLogic for the shared dispatch and default behavior
 */
public interface PlatformStatusLogic {
    /**
     * Process a {@link PlatformStatusAction}.
     *
     * @param action the action to process
     * @return the {@link PlatformStatusLogic} to manage the resulting status
     */
    @NonNull
    PlatformStatusLogic process(@NonNull PlatformStatusAction action);

    /**
     * Get the status that this logic is for.
     * <p>
     * A class implementing PlatformStatusLogic must always return the exact same status (i.e. no changing the status at
     * runtime within the same status logic class).
     *
     * @return the status that this logic is for
     */
    @NonNull
    PlatformStatus getStatus();
}
