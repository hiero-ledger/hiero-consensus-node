// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.status.logic;

import com.swirlds.platform.system.status.IllegalPlatformStatusException;
import com.swirlds.platform.system.status.PlatformStatusConfig;
import com.swirlds.platform.system.status.actions.CatastrophicFailureAction;
import com.swirlds.platform.system.status.actions.DoneReplayingEventsAction;
import com.swirlds.platform.system.status.actions.FallenBehindAction;
import com.swirlds.platform.system.status.actions.FreezePeriodEnteredAction;
import com.swirlds.platform.system.status.actions.ReconnectCompleteAction;
import com.swirlds.platform.system.status.actions.SelfEventReachedConsensusAction;
import com.swirlds.platform.system.status.actions.StartedReplayingEventsAction;
import com.swirlds.platform.system.status.actions.StateWrittenToDiskAction;
import com.swirlds.platform.system.status.actions.TimeElapsedAction;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Class containing the state machine logic for the {@link PlatformStatus#REPLAYING_EVENTS} status.
 */
public class ReplayingEventsStatusLogic implements PlatformStatusLogic {
    /**
     * The platform status config
     */
    private final PlatformStatusConfig config;

    /**
     * The round number of the freeze period if one has been entered, otherwise null
     */
    private Long freezeRound = null;

    /**
     * Constructor
     *
     * @param config the platform status config
     */
    public ReplayingEventsStatusLogic(@NonNull final PlatformStatusConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    /**
     * {@inheritDoc}
     * <p>
     * {@link PlatformStatus#REPLAYING_EVENTS} status unconditionally transitions to
     * {@link PlatformStatus#CATASTROPHIC_FAILURE} when a {@link CatastrophicFailureAction} is processed.
     */
    @NonNull
    @Override
    public PlatformStatusLogic processCatastrophicFailureAction(@NonNull final CatastrophicFailureAction action) {
        return new CatastrophicFailureStatusLogic();
    }

    /**
     * {@inheritDoc}
     * <p>
     * If a freeze boundary wasn't crossed while replaying events, then receiving a {@link DoneReplayingEventsAction}
     * causes a transition to {@link PlatformStatus#OBSERVING}.
     * <p>
     * If a freeze boundary was crossed while replaying events, then the {@link DoneReplayingEventsAction} doesn't
     * affect the state machine. The status will remain in {@link PlatformStatus#REPLAYING_EVENTS} until the freeze
     * state has been saved.
     */
    @NonNull
    @Override
    public PlatformStatusLogic processDoneReplayingEventsAction(@NonNull final DoneReplayingEventsAction action) {
        if (freezeRound != null) {
            // if a freeze boundary was crossed, we won't transition out of this state until the freeze state
            // has been saved
            return this;
        } else {
            return new ObservingStatusLogic(action.instant(), config);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Receiving a {@link FallenBehindAction} while in {@link PlatformStatus#REPLAYING_EVENTS} throws an exception,
     * since this is not conceivable in standard operation.
     */
    @NonNull
    @Override
    public PlatformStatusLogic processFallenBehindAction(@NonNull final FallenBehindAction action) {
        Objects.requireNonNull(action);

        throw new IllegalPlatformStatusException(action, getStatus());
    }

    /**
     * {@inheritDoc}
     * <p>
     * Receiving a {@link FreezePeriodEnteredAction} while in {@link PlatformStatus#REPLAYING_EVENTS} doesn't ever
     * result in a status transition, but this logic method does record the freeze round, which will inform the status
     * progression later.
     */
    @NonNull
    @Override
    public PlatformStatusLogic processFreezePeriodEnteredAction(@NonNull final FreezePeriodEnteredAction action) {
        if (freezeRound != null) {
            throw new IllegalPlatformStatusException(
                    "Received duplicate freeze period notification in REPLAYING_EVENTS status. Previous notification was for round "
                            + freezeRound + ", new notification is for round " + action.freezeRound());
        }

        freezeRound = action.freezeRound();
        return this;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Receiving a {@link ReconnectCompleteAction} while in {@link PlatformStatus#REPLAYING_EVENTS} throws an exception,
     * since this is not conceivable in standard operation.
     */
    @NonNull
    @Override
    public PlatformStatusLogic processReconnectCompleteAction(@NonNull final ReconnectCompleteAction action) {
        Objects.requireNonNull(action);

        throw new IllegalPlatformStatusException(action, getStatus());
    }

    /**
     * {@inheritDoc}
     * <p>
     * Receiving a {@link SelfEventReachedConsensusAction} while in {@link PlatformStatus#REPLAYING_EVENTS} has no
     * effect on the state machine.
     */
    @NonNull
    @Override
    public PlatformStatusLogic processSelfEventReachedConsensusAction(
            @NonNull final SelfEventReachedConsensusAction action) {

        return this;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Receiving a {@link StartedReplayingEventsAction} while in {@link PlatformStatus#REPLAYING_EVENTS} throws an
     * exception, since this is not conceivable in standard operation.
     */
    @NonNull
    @Override
    public PlatformStatusLogic processStartedReplayingEventsAction(@NonNull final StartedReplayingEventsAction action) {
        Objects.requireNonNull(action);

        throw new IllegalPlatformStatusException(action, getStatus());
    }

    /**
     * {@inheritDoc}
     * <p>
     * Receiving a {@link StateWrittenToDiskAction} while in {@link PlatformStatus#REPLAYING_EVENTS} causes a transition to
     * {@link PlatformStatus#FREEZE_COMPLETE} if it's a freeze state.
     */
    @NonNull
    @Override
    public PlatformStatusLogic processStateWrittenToDiskAction(@NonNull final StateWrittenToDiskAction action) {
        Objects.requireNonNull(action);

        return action.isFreezeState() ? new FreezeCompleteStatusLogic() : this;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Receiving a {@link TimeElapsedAction} while in {@link PlatformStatus#REPLAYING_EVENTS} has no effect on the state
     * machine.
     */
    @NonNull
    @Override
    public PlatformStatusLogic processTimeElapsedAction(@NonNull final TimeElapsedAction action) {
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public PlatformStatus getStatus() {
        return PlatformStatus.REPLAYING_EVENTS;
    }
}
