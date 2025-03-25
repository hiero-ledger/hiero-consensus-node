// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.status.logic;

import com.swirlds.common.utility.DurationUtils;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Class containing the state machine logic for the {@link PlatformStatus#ACTIVE} status.
 */
public class ActiveStatusLogic implements PlatformStatusLogic {
    /**
     * The platform status config
     */
    private final PlatformStatusConfig config;

    /**
     * The last wall clock time a self event was observed reaching consensus
     */
    private Instant lastWallClockTimeSelfEventReachedConsensus;

    /**
     * Constructor
     *
     * @param lastWallClockTimeSelfEventReachedConsensus the wall clock time when the self event that caused the
     *                                                   transition to {@link PlatformStatus#ACTIVE} reached consensus
     * @param config                                     the platform status config
     */
    public ActiveStatusLogic(
            @NonNull final Instant lastWallClockTimeSelfEventReachedConsensus,
            @NonNull final PlatformStatusConfig config) {

        this.lastWallClockTimeSelfEventReachedConsensus =
                Objects.requireNonNull(lastWallClockTimeSelfEventReachedConsensus);
        this.config = Objects.requireNonNull(config);
    }

    /**
     * {@inheritDoc}
     * <p>
     * {@link PlatformStatus#ACTIVE} status unconditionally transitions to {@link PlatformStatus#CATASTROPHIC_FAILURE}
     * when a {@link CatastrophicFailureAction} is processed.
     */
    @NonNull
    @Override
    public PlatformStatusLogic processCatastrophicFailureAction(@NonNull final CatastrophicFailureAction action) {
        return new CatastrophicFailureStatusLogic();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Receiving a {@link DoneReplayingEventsAction} while in {@link PlatformStatus#ACTIVE} throws an exception, since
     * this is not conceivable in standard operation.
     */
    @NonNull
    @Override
    public PlatformStatusLogic processDoneReplayingEventsAction(@NonNull final DoneReplayingEventsAction action) {
        Objects.requireNonNull(action);

        throw new IllegalPlatformStatusException(action, getStatus());
    }

    /**
     * {@inheritDoc}
     * <p>
     * {@link PlatformStatus#ACTIVE} status unconditionally transitions to {@link PlatformStatus#BEHIND} when a
     * {@link FallenBehindAction} is processed.
     */
    @NonNull
    @Override
    public PlatformStatusLogic processFallenBehindAction(@NonNull final FallenBehindAction action) {
        return new BehindStatusLogic(config);
    }

    /**
     * {@inheritDoc}
     * <p>
     * {@link PlatformStatus#ACTIVE} status unconditionally transitions to {@link PlatformStatus#FREEZING} when a
     * {@link FreezePeriodEnteredAction} is processed.
     */
    @NonNull
    @Override
    public PlatformStatusLogic processFreezePeriodEnteredAction(@NonNull final FreezePeriodEnteredAction action) {
        Objects.requireNonNull(action);

        return new FreezingStatusLogic(action.freezeRound());
    }

    /**
     * {@inheritDoc}
     * <p>
     * Receiving a {@link ReconnectCompleteAction} while in {@link PlatformStatus#ACTIVE} throws an exception, since
     * this is not conceivable in standard operation.
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
     * Receiving a {@link SelfEventReachedConsensusAction} while in {@link PlatformStatus#ACTIVE} doesn't ever result in
     * a status transition, but this logic method does record the wall clock time the event reached consensus.
     */
    @NonNull
    @Override
    public PlatformStatusLogic processSelfEventReachedConsensusAction(
            @NonNull final SelfEventReachedConsensusAction action) {

        lastWallClockTimeSelfEventReachedConsensus = action.wallClockTime();
        return this;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Receiving a {@link StartedReplayingEventsAction} while in {@link PlatformStatus#ACTIVE} throws an exception,
     * since this is not conceivable in standard operation.
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
     * Receiving a {@link StateWrittenToDiskAction} while in {@link PlatformStatus#ACTIVE} causes a transition to
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
     * When a {@link TimeElapsedAction} is received while in {@link PlatformStatus#ACTIVE}, we must evaluate whether too
     * much time has elapsed since seeing a self event reach consensus. If too much time has elapsed, the status
     * transitions to {@link PlatformStatus#CHECKING}. Otherwise, the status remains {@link PlatformStatus#ACTIVE}.
     */
    @NonNull
    @Override
    public PlatformStatusLogic processTimeElapsedAction(@NonNull final TimeElapsedAction action) {
        final Duration timeSinceSelfEventReachedConsensus =
                Duration.between(lastWallClockTimeSelfEventReachedConsensus, action.instant());

        if (DurationUtils.isLonger(timeSinceSelfEventReachedConsensus, config.activeStatusDelay())) {
            return new CheckingStatusLogic(config);
        } else {
            return this;
        }
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public PlatformStatus getStatus() {
        return PlatformStatus.ACTIVE;
    }
}
