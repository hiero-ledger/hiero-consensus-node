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
 * Class containing the state machine logic for the {@link PlatformStatus#STARTING_UP} status.
 */
public class StartingUpStatusLogic implements PlatformStatusLogic {
    /**
     * The platform status config
     */
    private final PlatformStatusConfig config;

    /**
     * Constructor
     *
     * @param config the platform status config
     */
    public StartingUpStatusLogic(@NonNull final PlatformStatusConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    /**
     * {@inheritDoc}
     * <p>
     * {@link PlatformStatus#STARTING_UP} status unconditionally transitions to
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
     * Receiving a {@link DoneReplayingEventsAction} while in {@link PlatformStatus#STARTING_UP} throws an exception,
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
     * Receiving a {@link FallenBehindAction} while in {@link PlatformStatus#STARTING_UP} throws an exception, since
     * this is not conceivable in standard operation.
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
     * Receiving a {@link FreezePeriodEnteredAction} while in {@link PlatformStatus#STARTING_UP} throws an exception,
     * since this is not conceivable in standard operation.
     */
    @NonNull
    @Override
    public PlatformStatusLogic processFreezePeriodEnteredAction(@NonNull final FreezePeriodEnteredAction action) {
        Objects.requireNonNull(action);

        throw new IllegalPlatformStatusException(action, getStatus());
    }

    /**
     * {@inheritDoc}
     * <p>
     * Receiving a {@link ReconnectCompleteAction} while in {@link PlatformStatus#STARTING_UP} throws an exception,
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
     * Receiving a {@link SelfEventReachedConsensusAction} while in {@link PlatformStatus#STARTING_UP} throws an
     * exception, since this is not conceivable in standard operation.
     */
    @NonNull
    @Override
    public PlatformStatusLogic processSelfEventReachedConsensusAction(
            @NonNull final SelfEventReachedConsensusAction action) {

        Objects.requireNonNull(action);

        throw new IllegalPlatformStatusException(action, getStatus());
    }

    /**
     * {@inheritDoc}
     * <p>
     * {@link PlatformStatus#STARTING_UP} status unconditionally transitions to {@link PlatformStatus#REPLAYING_EVENTS}
     * when a {@link StartedReplayingEventsAction} is processed.
     */
    @NonNull
    @Override
    public PlatformStatusLogic processStartedReplayingEventsAction(@NonNull final StartedReplayingEventsAction action) {
        return new ReplayingEventsStatusLogic(config);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Receiving a {@link StateWrittenToDiskAction} while in {@link PlatformStatus#STARTING_UP} throws an exception,
     * since this is not conceivable in standard operation.
     */
    @NonNull
    @Override
    public PlatformStatusLogic processStateWrittenToDiskAction(@NonNull final StateWrittenToDiskAction action) {
        Objects.requireNonNull(action);

        throw new IllegalPlatformStatusException(action, getStatus());
    }

    /**
     * {@inheritDoc}
     * <p>
     * Receiving a {@link TimeElapsedAction} while in {@link PlatformStatus#STARTING_UP} has no effect on the state
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
        return PlatformStatus.STARTING_UP;
    }
}
