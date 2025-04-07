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
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Class containing the state machine logic for the {@link PlatformStatus#RECONNECT_COMPLETE} status.
 */
public class ReconnectCompleteStatusLogic implements PlatformStatusLogic {
    /**
     * The platform status config
     */
    private final PlatformStatusConfig config;

    /**
     * The round number of the reconnect state that was received
     */
    private final long reconnectStateRound;

    /**
     * The round number of the freeze period if one has been entered, otherwise null
     */
    private Long freezeRound;

    /**
     * Constructor
     *
     * @param reconnectStateRound the round number of the reconnect state that was received
     * @param freezeRound         the round number of the freeze period if one has been entered, otherwise null
     * @param config              the platform status config
     */
    public ReconnectCompleteStatusLogic(
            final long reconnectStateRound,
            @Nullable final Long freezeRound,
            @NonNull final PlatformStatusConfig config) {

        this.reconnectStateRound = reconnectStateRound;
        this.freezeRound = freezeRound;
        this.config = Objects.requireNonNull(config);
    }

    /**
     * {@inheritDoc}
     * <p>
     * {@link PlatformStatus#RECONNECT_COMPLETE} status unconditionally transitions to
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
     * Receiving a {@link DoneReplayingEventsAction} while in {@link PlatformStatus#RECONNECT_COMPLETE} throws an
     * exception, since this is not conceivable in standard operation.
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
     * {@link PlatformStatus#RECONNECT_COMPLETE} status unconditionally transitions to {@link PlatformStatus#BEHIND}
     * when a {@link FallenBehindAction} is processed.
     */
    @NonNull
    @Override
    public PlatformStatusLogic processFallenBehindAction(@NonNull final FallenBehindAction action) {
        return new BehindStatusLogic(config);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Receiving a {@link FreezePeriodEnteredAction} while in {@link PlatformStatus#RECONNECT_COMPLETE} doesn't ever
     * result in a status transition, but this logic method does record the freeze round, which will inform the status
     * progression once the reconnect state has been saved.
     */
    @NonNull
    @Override
    public PlatformStatusLogic processFreezePeriodEnteredAction(@NonNull final FreezePeriodEnteredAction action) {
        if (freezeRound != null) {
            throw new IllegalPlatformStatusException(
                    "Received duplicate freeze period notification in RECONNECT_COMPLETE status. Previous notification was for round "
                            + freezeRound + ", new notification is for round " + action.freezeRound());
        }

        freezeRound = action.freezeRound();
        return this;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Receiving a {@link ReconnectCompleteAction} while in {@link PlatformStatus#RECONNECT_COMPLETE} throws an
     * exception, since this is not conceivable in standard operation.
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
     * Receiving a {@link SelfEventReachedConsensusAction} while in {@link PlatformStatus#RECONNECT_COMPLETE} has no
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
     * Receiving a {@link StartedReplayingEventsAction} while in {@link PlatformStatus#RECONNECT_COMPLETE} throws an
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
     * Receiving a {@link StateWrittenToDiskAction} while in {@link PlatformStatus#RECONNECT_COMPLETE} causes a
     * transition to {@link PlatformStatus#FREEZE_COMPLETE} if it's a freeze state.
     * <p>
     * For non-freeze states, if the state written to disk is prior to the reconnect state round, it's old, so we need
     * to wait until the reconnect state is written to disk (or a later state). If the state written to disk is the
     * reconnect state or later, then we can transition to a new status. If a freeze boundary has been crossed, we
     * transition to {@link PlatformStatus#FREEZING} status. Otherwise, we transition to
     * {@link PlatformStatus#CHECKING} status.
     */
    @NonNull
    @Override
    public PlatformStatusLogic processStateWrittenToDiskAction(@NonNull final StateWrittenToDiskAction action) {
        if (action.isFreezeState()) {
            return new FreezeCompleteStatusLogic();
        }

        if (action.round() < reconnectStateRound) {
            // if the state written to disk is prior to the reconnect state round, it's old.
            // we need to wait until the reconnected state is written to disk (or a later state)
            return this;
        }

        // always transition to a new status once the reconnect state has been written to disk
        if (freezeRound != null) {
            return new FreezingStatusLogic(freezeRound);
        } else {
            return new CheckingStatusLogic(config);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Receiving a {@link TimeElapsedAction} while in {@link PlatformStatus#RECONNECT_COMPLETE} has no effect on the
     * state machine.
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
        return PlatformStatus.RECONNECT_COMPLETE;
    }
}
