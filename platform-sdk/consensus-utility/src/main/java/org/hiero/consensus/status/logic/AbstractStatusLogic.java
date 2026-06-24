// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.logic;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;
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
 * Base class for {@link PlatformStatusLogic} implementations.
 * <p>
 * It dispatches each {@link PlatformStatusAction} to a per-action {@code on*} hook and supplies the behavior shared by
 * most statuses, so that a concrete subclass only overrides the hooks for the actions that actually drive its logic.
 * The defaults are:
 * <ul>
 *     <li>a {@link CatastrophicFailureAction} transitions to {@link PlatformStatus#CATASTROPHIC_FAILURE};</li>
 *     <li>a {@link StateWrittenToDiskAction} transitions to {@link PlatformStatus#FREEZE_COMPLETE} when it is a freeze
 *     state, otherwise it has no effect;</li>
 *     <li>a {@link TimeElapsedAction} and a {@link SelfEventReachedConsensusAction} have no effect;</li>
 *     <li>every other action is illegal and throws an {@link IllegalPlatformStatusException}.</li>
 * </ul>
 * Terminal statuses ({@link PlatformStatus#CATASTROPHIC_FAILURE}, {@link PlatformStatus#FREEZE_COMPLETE}) ignore every
 * action, so they implement {@link PlatformStatusLogic} directly rather than extending this class.
 */
public abstract class AbstractStatusLogic implements PlatformStatusLogic {

    private final PlatformStatus status;

    protected AbstractStatusLogic(@NonNull final PlatformStatus status) {
        this.status = Objects.requireNonNull(status);
    }

    @NonNull
    @Override
    public final PlatformStatusLogic process(@NonNull final PlatformStatusAction action) {
        return switch (action) {
            case CatastrophicFailureAction _ -> onCatastrophicFailure();
            case DoneReplayingEventsAction a -> onDoneReplayingEvents(a);
            case FallenBehindAction a -> onFallenBehind(a);
            case FreezePeriodEnteredAction a -> onFreezePeriodEntered(a);
            case ReconnectCompleteAction a -> onReconnectComplete(a);
            case SelfEventReachedConsensusAction a -> onSelfEventReachedConsensus(a);
            case StartedReplayingEventsAction a -> onStartedReplayingEvents(a);
            case StateWrittenToDiskAction a -> onStateWrittenToDisk(a);
            case TimeElapsedAction a -> onTimeElapsed(a);
        };
    }

    @NonNull
    @Override
    public final PlatformStatus getStatus() {
        return status;
    }

    // --- per-action hooks; defaults are the behavior shared by most statuses ---

    @NonNull
    protected PlatformStatusLogic onCatastrophicFailure() {
        return new CatastrophicFailureStatusLogic();
    }

    @NonNull
    protected PlatformStatusLogic onDoneReplayingEvents(@NonNull final DoneReplayingEventsAction action) {
        return illegal(action);
    }

    @NonNull
    protected PlatformStatusLogic onFallenBehind(@NonNull final FallenBehindAction action) {
        return illegal(action);
    }

    @NonNull
    protected PlatformStatusLogic onFreezePeriodEntered(@NonNull final FreezePeriodEnteredAction action) {
        return illegal(action);
    }

    @NonNull
    protected PlatformStatusLogic onReconnectComplete(@NonNull final ReconnectCompleteAction action) {
        return illegal(action);
    }

    @NonNull
    protected PlatformStatusLogic onSelfEventReachedConsensus(@NonNull final SelfEventReachedConsensusAction action) {
        return this;
    }

    @NonNull
    protected PlatformStatusLogic onStartedReplayingEvents(@NonNull final StartedReplayingEventsAction action) {
        return illegal(action);
    }

    @NonNull
    protected PlatformStatusLogic onStateWrittenToDisk(@NonNull final StateWrittenToDiskAction action) {
        return action.isFreezeState() ? new FreezeCompleteStatusLogic() : this;
    }

    @NonNull
    protected PlatformStatusLogic onTimeElapsed(@NonNull final TimeElapsedAction action) {
        return this;
    }

    /**
     * Validate a freeze round for a status that notes the freeze boundary without transitioning, throwing if a freeze
     * round was already recorded.
     *
     * @param existingFreezeRound the freeze round already recorded, or {@code null} if none
     * @param action              the freeze period action being processed
     */
    protected void validateFreezeRound(
            @Nullable final Long existingFreezeRound, @NonNull final FreezePeriodEnteredAction action) {

        if (existingFreezeRound != null) {
            throw new IllegalPlatformStatusException("Received duplicate freeze period notification in " + status
                    + " status. Previous notification was for round " + existingFreezeRound
                    + ", new notification is for round " + action.freezeRound());
        }
    }

    /**
     * Throw an {@link IllegalPlatformStatusException} indicating the action is not valid for the current status.
     *
     * @param action the action that is not valid
     * @return never returns; always throws
     */
    @NonNull
    protected final PlatformStatusLogic illegal(@NonNull final PlatformStatusAction action) {
        throw new IllegalPlatformStatusException(action, getStatus());
    }
}
