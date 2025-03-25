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
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Class containing the state machine logic for the {@link PlatformStatus#OBSERVING} status.
 */
public class ObservingStatusLogic implements PlatformStatusLogic {
    /**
     * The platform status config
     */
    private final PlatformStatusConfig config;

    /**
     * The time at which the platform entered the {@link PlatformStatus#OBSERVING} status
     */
    private final Instant statusStartTime;

    /**
     * The round number of the freeze period if one has been entered, otherwise null
     */
    private Long freezeRound = null;

    /**
     * Constructor
     *
     * @param statusStartTime the time at which the platform entered the {@link PlatformStatus#OBSERVING} status
     * @param config          the platform status config
     */
    public ObservingStatusLogic(@NonNull final Instant statusStartTime, @NonNull final PlatformStatusConfig config) {
        this.statusStartTime = Objects.requireNonNull(statusStartTime);
        this.config = Objects.requireNonNull(config);
    }

    /**
     * {@inheritDoc}
     * <p>
     * {@link PlatformStatus#OBSERVING} status unconditionally transitions to
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
     * Receiving a {@link DoneReplayingEventsAction} while in {@link PlatformStatus#OBSERVING} throws an exception,
     * since this is not conceivable in standard operation.
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
     * {@link PlatformStatus#OBSERVING} status unconditionally transitions to {@link PlatformStatus#BEHIND} when a
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
     * Receiving a {@link FreezePeriodEnteredAction} while in {@link PlatformStatus#OBSERVING} doesn't ever result in a
     * status transition, but this logic method does record the freeze round, which will inform the status progression
     * once the observing period has elapsed
     */
    @NonNull
    @Override
    public PlatformStatusLogic processFreezePeriodEnteredAction(@NonNull final FreezePeriodEnteredAction action) {
        if (freezeRound != null) {
            throw new IllegalPlatformStatusException(
                    "Received duplicate freeze period notification in OBSERVING status. Previous notification was for round "
                            + freezeRound + ", new notification is for round " + action.freezeRound());
        }

        freezeRound = action.freezeRound();
        return this;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Receiving a {@link ReconnectCompleteAction} while in {@link PlatformStatus#OBSERVING} throws an exception, since
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
     * Receiving a {@link SelfEventReachedConsensusAction} while in {@link PlatformStatus#OBSERVING} has no effect on
     * the state machine.
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
     * Receiving a {@link StartedReplayingEventsAction} while in {@link PlatformStatus#OBSERVING} throws an exception,
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
     * Receiving a {@link StateWrittenToDiskAction} while in {@link PlatformStatus#OBSERVING} causes a transition to
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
     * {@link PlatformStatus#OBSERVING} status always transitions to a new status once the observation period has
     * elapsed.
     * <p>
     * The status transitions to {@link PlatformStatus#FREEZING} if a freeze period was entered during the observation
     * period, otherwise it transitions to {@link PlatformStatus#CHECKING}.
     */
    @NonNull
    @Override
    public PlatformStatusLogic processTimeElapsedAction(@NonNull final TimeElapsedAction action) {
        if (Duration.between(statusStartTime, action.instant()).compareTo(config.observingStatusDelay()) < 0) {
            // if the wait period hasn't elapsed, then stay in this status
            return this;
        }

        if (freezeRound != null) {
            return new FreezingStatusLogic(freezeRound);
        } else {
            return new CheckingStatusLogic(config);
        }
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public PlatformStatus getStatus() {
        return PlatformStatus.OBSERVING;
    }
}
