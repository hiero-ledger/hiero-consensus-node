// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.logic;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.status.IllegalPlatformStatusException;
import org.hiero.consensus.status.actions.CatastrophicFailureAction;
import org.hiero.consensus.status.actions.DoneReplayingEventsAction;
import org.hiero.consensus.status.actions.FallenBehindAction;
import org.hiero.consensus.status.actions.FreezePeriodEnteredAction;
import org.hiero.consensus.status.actions.PlatformStatusAction;
import org.hiero.consensus.status.actions.ReconnectCompleteAction;
import org.hiero.consensus.status.actions.SelfEventReachedConsensusAction;
import org.hiero.consensus.status.actions.StartedReplayingEventsAction;
import org.hiero.consensus.status.actions.StateWrittenToDiskAction;
import org.hiero.consensus.status.actions.TimeElapsedAction;

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
