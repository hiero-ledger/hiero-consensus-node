// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.logic;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.status.IllegalPlatformStatusException;
import org.hiero.consensus.status.triggers.CatastrophicFailureTrigger;
import org.hiero.consensus.status.triggers.DoneReplayingEventsTrigger;
import org.hiero.consensus.status.triggers.FallenBehindTrigger;
import org.hiero.consensus.status.triggers.FreezePeriodEnteredTrigger;
import org.hiero.consensus.status.triggers.ReconnectCompleteTrigger;
import org.hiero.consensus.status.triggers.SelfEventReachedConsensusTrigger;
import org.hiero.consensus.status.triggers.StartedReplayingEventsTrigger;
import org.hiero.consensus.status.triggers.StateWrittenToDiskTrigger;
import org.hiero.consensus.status.triggers.StatusMachineTrigger;
import org.hiero.consensus.status.triggers.TimeElapsedTrigger;

/**
 * Base class for {@link PlatformStatusLogic} implementations.
 * <p>
 * It dispatches each {@link StatusMachineTrigger} to a per-trigger {@code on*} hook and supplies the behavior shared by
 * most statuses, so that a concrete subclass only overrides the hooks for the triggers that actually drive its logic.
 * The defaults are:
 * <ul>
 *     <li>a {@link CatastrophicFailureTrigger} transitions to {@link PlatformStatus#CATASTROPHIC_FAILURE};</li>
 *     <li>a {@link StateWrittenToDiskTrigger} transitions to {@link PlatformStatus#FREEZE_COMPLETE} when it is a freeze
 *     state, otherwise it has no effect;</li>
 *     <li>a {@link TimeElapsedTrigger} and a {@link SelfEventReachedConsensusTrigger} have no effect;</li>
 *     <li>every other trigger is illegal and throws an {@link IllegalPlatformStatusException}.</li>
 * </ul>
 * Terminal statuses ({@link PlatformStatus#CATASTROPHIC_FAILURE}, {@link PlatformStatus#FREEZE_COMPLETE}) ignore every
 * trigger, so they implement {@link PlatformStatusLogic} directly rather than extending this class.
 */
public abstract class AbstractStatusLogic implements PlatformStatusLogic {

    private final PlatformStatus status;

    protected AbstractStatusLogic(@NonNull final PlatformStatus status) {
        this.status = Objects.requireNonNull(status);
    }

    @NonNull
    @Override
    public final PlatformStatusLogic process(@NonNull final StatusMachineTrigger trigger) {
        return switch (trigger) {
            case CatastrophicFailureTrigger a -> onCatastrophicFailure(a);
            case DoneReplayingEventsTrigger a -> onDoneReplayingEvents(a);
            case FallenBehindTrigger a -> onFallenBehind(a);
            case FreezePeriodEnteredTrigger a -> onFreezePeriodEntered(a);
            case ReconnectCompleteTrigger a -> onReconnectComplete(a);
            case SelfEventReachedConsensusTrigger a -> onSelfEventReachedConsensus(a);
            case StartedReplayingEventsTrigger a -> onStartedReplayingEvents(a);
            case StateWrittenToDiskTrigger a -> onStateWrittenToDisk(a);
            case TimeElapsedTrigger a -> onTimeElapsed(a);
        };
    }

    @NonNull
    @Override
    public final PlatformStatus getStatus() {
        return status;
    }

    // --- per-trigger hooks; defaults are the behavior shared by most statuses ---

    @NonNull
    protected PlatformStatusLogic onCatastrophicFailure(@NonNull final CatastrophicFailureTrigger trigger) {
        return new CatastrophicFailureStatusLogic();
    }

    @NonNull
    protected PlatformStatusLogic onDoneReplayingEvents(@NonNull final DoneReplayingEventsTrigger trigger) {
        return illegal(trigger);
    }

    @NonNull
    protected PlatformStatusLogic onFallenBehind(@NonNull final FallenBehindTrigger trigger) {
        return illegal(trigger);
    }

    @NonNull
    protected PlatformStatusLogic onFreezePeriodEntered(@NonNull final FreezePeriodEnteredTrigger trigger) {
        return illegal(trigger);
    }

    @NonNull
    protected PlatformStatusLogic onReconnectComplete(@NonNull final ReconnectCompleteTrigger trigger) {
        return illegal(trigger);
    }

    @NonNull
    protected PlatformStatusLogic onSelfEventReachedConsensus(@NonNull final SelfEventReachedConsensusTrigger trigger) {
        return this;
    }

    @NonNull
    protected PlatformStatusLogic onStartedReplayingEvents(@NonNull final StartedReplayingEventsTrigger trigger) {
        return illegal(trigger);
    }

    @NonNull
    protected PlatformStatusLogic onStateWrittenToDisk(@NonNull final StateWrittenToDiskTrigger trigger) {
        return trigger.isFreezeState() ? new FreezeCompleteStatusLogic() : this;
    }

    @NonNull
    protected PlatformStatusLogic onTimeElapsed(@NonNull final TimeElapsedTrigger trigger) {
        return this;
    }

    /**
     * Validate a freeze round for a status that notes the freeze boundary without transitioning, throwing if a freeze
     * round was already recorded.
     *
     * @param existingFreezeRound the freeze round already recorded, or {@code null} if none
     * @param trigger              the freeze period trigger being processed
     * @return the freeze round to record
     */
    protected final long validateFreezeRound(
            @Nullable final Long existingFreezeRound, @NonNull final FreezePeriodEnteredTrigger trigger) {

        if (existingFreezeRound != null) {
            throw new IllegalPlatformStatusException("Received duplicate freeze period notification in " + status
                    + " status. Previous notification was for round " + existingFreezeRound
                    + ", new notification is for round " + trigger.freezeRound());
        }
        return trigger.freezeRound();
    }

    /**
     * Throw an {@link IllegalPlatformStatusException} indicating the trigger is not valid for the current status.
     *
     * @param trigger the trigger that is not valid
     * @return never returns; always throws
     */
    @NonNull
    protected final PlatformStatusLogic illegal(@NonNull final StatusMachineTrigger trigger) {
        throw new IllegalPlatformStatusException(trigger, getStatus());
    }
}
