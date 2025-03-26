// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.nexus;

import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.config.StateConfig;
import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.model.hashgraph.EventWindow;

/**
 * The default implementation of {@link LatestCompleteStateNexus}.
 */
public class DefaultLatestCompleteStateNexus implements LatestCompleteStateNexus {
    private static final RunningAverageMetric.Config AVG_ROUND_SUPERMAJORITY_CONFIG = new RunningAverageMetric.Config(
                    PLATFORM_CATEGORY, "roundSup")
            .withDescription("latest round with state signed by a supermajority")
            .withUnit("round");

    private final StateConfig stateConfig;
    private ReservedSignedState currentState;

    /**
     * Create a new nexus that holds the latest complete signed state.
     *
     * @param platformContext the platform context
     */
    public DefaultLatestCompleteStateNexus(@NonNull final PlatformContext platformContext) {
        this.stateConfig = platformContext.getConfiguration().getConfigData(StateConfig.class);

        final Metrics metrics = platformContext.getMetrics();
        final RunningAverageMetric avgRoundSupermajority = metrics.getOrCreate(AVG_ROUND_SUPERMAJORITY_CONFIG);
        metrics.addUpdater(() -> avgRoundSupermajority.update(getRound()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void setState(@Nullable final ReservedSignedState reservedSignedState) {
        if (currentState != null) {
            currentState.close();
        }
        currentState = reservedSignedState;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void setStateIfNewer(@NonNull final ReservedSignedState reservedSignedState) {
        if (reservedSignedState.isNotNull()
                && getRound() < reservedSignedState.get().getRound()) {
            setState(reservedSignedState);
        } else {
            reservedSignedState.close();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void updateEventWindow(@NonNull final EventWindow eventWindow) {
        // Any state older than this is unconditionally removed, even if it is the latest
        final long earliestPermittedRound =
                eventWindow.getLatestConsensusRound() - stateConfig.roundsToKeepForSigning() + 1;

        // Is the latest complete round older than the earliest permitted round?
        if (getRound() < earliestPermittedRound) {
            // Yes, so remove it
            clear();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public synchronized ReservedSignedState getState(@NonNull final String reason) {
        if (currentState == null) {
            return null;
        }
        return currentState.tryGetAndReserve(reason);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized long getRound() {
        if (currentState == null) {
            return ConsensusConstants.ROUND_UNDEFINED;
        }
        return currentState.get().getRound();
    }
}
