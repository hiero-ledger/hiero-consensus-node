// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.status.logic;

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
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Class containing the state machine logic for the {@link PlatformStatus#FREEZE_COMPLETE} status.
 * <p>
 * This status is terminal, and no action can cause the status to transition from it.
 */
public class FreezeCompleteStatusLogic implements PlatformStatusLogic {
    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public PlatformStatusLogic processCatastrophicFailureAction(@NonNull final CatastrophicFailureAction action) {
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public PlatformStatusLogic processDoneReplayingEventsAction(@NonNull final DoneReplayingEventsAction action) {
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public PlatformStatusLogic processFallenBehindAction(@NonNull final FallenBehindAction action) {
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public PlatformStatusLogic processFreezePeriodEnteredAction(@NonNull final FreezePeriodEnteredAction action) {
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public PlatformStatusLogic processReconnectCompleteAction(@NonNull final ReconnectCompleteAction action) {
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public PlatformStatusLogic processSelfEventReachedConsensusAction(
            @NonNull final SelfEventReachedConsensusAction action) {

        return this;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public PlatformStatusLogic processStartedReplayingEventsAction(@NonNull final StartedReplayingEventsAction action) {
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public PlatformStatusLogic processStateWrittenToDiskAction(@NonNull final StateWrittenToDiskAction action) {
        return this;
    }

    /**
     * {@inheritDoc}
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
        return PlatformStatus.FREEZE_COMPLETE;
    }
}
