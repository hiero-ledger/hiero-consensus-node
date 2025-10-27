// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import com.swirlds.platform.freeze.FreezePeriodChecker;
import com.swirlds.platform.state.service.PlatformStateFacade;
import java.time.Instant;

/**
 * Default implementation of {@link FreezePeriodChecker}. Uses the latest state from {@link SwirldStateManager} to
 * answer the question of whether the given timestamp is within the freeze period.
 * Also, updates the last frozen time upon request.
 */
public class DefaultFreezePeriodChecker implements FreezePeriodChecker {

    private final SwirldStateManager swirldStateManager;
    private final PlatformStateFacade platformStateFacade;

    public DefaultFreezePeriodChecker(SwirldStateManager swirldStateManager, PlatformStateFacade platformStateFacade) {
        this.swirldStateManager = swirldStateManager;
        this.platformStateFacade = platformStateFacade;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isInFreezePeriod(final Instant timestamp) {
        return PlatformStateFacade.isInFreezePeriod(
                timestamp,
                platformStateFacade.freezeTimeOf(swirldStateManager.getConsensusState()),
                platformStateFacade.lastFrozenTimeOf(swirldStateManager.getConsensusState()));
    }

    /**
     * Invoked when a signed state is about to be created for the current freeze period.
     * <p>
     * Invoked only by the consensus handling thread, so there is no chance of the state being modified by a concurrent
     * thread.
     * </p>
     */
    public void savedStateInFreezePeriod() {
        // set current DualState's lastFrozenTime to be current freezeTime
        platformStateFacade.updateLastFrozenTime(swirldStateManager.getConsensusState());
    }
}
