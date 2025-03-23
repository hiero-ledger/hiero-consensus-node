// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.status.logic;

import com.swirlds.platform.system.status.IllegalPlatformStatusException;
import com.swirlds.platform.system.status.actions.CatastrophicFailureAction;
import com.swirlds.platform.system.status.actions.DoneReplayingEventsAction;
import com.swirlds.platform.system.status.actions.FallenBehindAction;
import com.swirlds.platform.system.status.actions.FreezePeriodEnteredAction;
import com.swirlds.platform.system.status.actions.PlatformStatusAction;
import com.swirlds.platform.system.status.actions.ReconnectCompleteAction;
import com.swirlds.platform.system.status.actions.SelfEventReachedConsensusAction;
import com.swirlds.platform.system.status.actions.StartedReplayingEventsAction;
import com.swirlds.platform.system.status.actions.StateWrittenToDiskAction;
import com.swirlds.platform.system.status.actions.TimeElapsedAction;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Interface representing the state machine logic for an individual {@link PlatformStatus}.
 * <p>
 * The methods in this interface that process {@link PlatformStatusAction}s behave in the following way:
 * <ul>
 *     <li>If the input action results in a status transition, the processing method should return an instance of
 *     {@link PlatformStatusLogic} corresponding to the new status</li>
 *     <li>If the input action does not result in a status transition, the processing method should return a reference
 *     to itself, since it will continue managing the logic for the current status status moving forward</li>
 *     <li>If the input action is not a valid for the current status, the processing method should throw an
 *     {@link IllegalPlatformStatusException IllegalPlatformStatusException}</li>
 * </ul>
 */
public interface PlatformStatusLogic {
    /**
     * Process a {@link CatastrophicFailureAction}
     *
     * @param action the action to process
     * @return the {@link PlatformStatusLogic} to manage the resulting status
     */
    @NonNull
    PlatformStatusLogic processCatastrophicFailureAction(@NonNull final CatastrophicFailureAction action);

    /**
     * Process a {@link DoneReplayingEventsAction}
     *
     * @param action the action to process
     * @return the {@link PlatformStatusLogic} to manage the resulting status
     */
    @NonNull
    PlatformStatusLogic processDoneReplayingEventsAction(@NonNull final DoneReplayingEventsAction action);

    /**
     * Process a {@link FallenBehindAction}
     *
     * @param action the action to process
     * @return the {@link PlatformStatusLogic} to manage the resulting status
     */
    @NonNull
    PlatformStatusLogic processFallenBehindAction(@NonNull final FallenBehindAction action);

    /**
     * Process a {@link FreezePeriodEnteredAction}
     *
     * @param action the action to process
     * @return the {@link PlatformStatusLogic} to manage the resulting status
     */
    @NonNull
    PlatformStatusLogic processFreezePeriodEnteredAction(@NonNull final FreezePeriodEnteredAction action);

    /**
     * Process a {@link ReconnectCompleteAction}
     *
     * @param action the action to process
     * @return the {@link PlatformStatusLogic} to manage the resulting status
     */
    @NonNull
    PlatformStatusLogic processReconnectCompleteAction(@NonNull final ReconnectCompleteAction action);

    /**
     * Process a {@link SelfEventReachedConsensusAction}
     *
     * @param action the action to process
     * @return the {@link PlatformStatusLogic} to manage the resulting status
     */
    @NonNull
    PlatformStatusLogic processSelfEventReachedConsensusAction(@NonNull final SelfEventReachedConsensusAction action);

    /**
     * Process a {@link StartedReplayingEventsAction}
     *
     * @param action the action to process
     * @return the {@link PlatformStatusLogic} to manage the resulting status
     */
    @NonNull
    PlatformStatusLogic processStartedReplayingEventsAction(@NonNull final StartedReplayingEventsAction action);

    /**
     * Process a {@link StateWrittenToDiskAction}
     *
     * @param action the action to process
     * @return the {@link PlatformStatusLogic} to manage the resulting status
     */
    @NonNull
    PlatformStatusLogic processStateWrittenToDiskAction(@NonNull final StateWrittenToDiskAction action);

    /**
     * Process a {@link TimeElapsedAction}
     *
     * @param action the action to process
     * @return the {@link PlatformStatusLogic} to manage the resulting status
     */
    @NonNull
    PlatformStatusLogic processTimeElapsedAction(@NonNull final TimeElapsedAction action);

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
