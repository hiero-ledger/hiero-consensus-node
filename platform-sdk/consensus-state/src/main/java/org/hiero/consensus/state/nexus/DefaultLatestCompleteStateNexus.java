// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.state.nexus;

import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;

import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.metrics.RunningAverageMetric;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.state.config.StateConfig;
import org.hiero.consensus.state.signed.ReservedSignedState;

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
    /** Once freezing begins, this nexus must remain empty for the rest of its lifetime. */
    private boolean freezePeriodEntered;

    /**
     * Create a new nexus that holds the latest complete signed state.
     *
     * @param configuration the configuration to use
     * @param metrics the metrics to use
     */
    public DefaultLatestCompleteStateNexus(@NonNull final Configuration configuration, @NonNull final Metrics metrics) {
        this.stateConfig = configuration.getConfigData(StateConfig.class);

        final RunningAverageMetric avgRoundSupermajority = metrics.getOrCreate(AVG_ROUND_SUPERMAJORITY_CONFIG);
        metrics.addUpdater(() -> avgRoundSupermajority.update(getRound()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void setState(@Nullable final ReservedSignedState reservedSignedState) {
        if (freezePeriodEntered && reservedSignedState != null) {
            reservedSignedState.close();
            return;
        }

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
        if (stateConfig.saveStateAsync()
                && reservedSignedState.isNotNull()
                && reservedSignedState.get().isFreezeState()) {
            enterFreezePeriod();
            reservedSignedState.close();
            return;
        }

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
    public synchronized void observeStateForAsyncFreeze(@NonNull final ReservedSignedState reservedSignedState) {
        try {
            if (stateConfig.saveStateAsync()
                    && reservedSignedState.isNotNull()
                    && reservedSignedState.get().isFreezeState()) {
                enterFreezePeriod();
            }
        } finally {
            reservedSignedState.close();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updatePlatformStatus(@NonNull final PlatformStatus platformStatus) {
        if (PlatformStatus.FREEZING.equals(platformStatus)) {
            synchronized (this) {
                if (stateConfig.saveStateAsync()) {
                    enterFreezePeriod();
                    return;
                }
                if (currentState == null) {
                    return;
                }
                currentState.close();
                currentState = null;
            }
        }
    }

    /**
     * Prevent this nexus from retaining any more states and release the state currently held by it.
     *
     * <p>This method must be called while holding this object's monitor.
     */
    private void enterFreezePeriod() {
        freezePeriodEntered = true;
        if (currentState != null) {
            currentState.close();
            currentState = null;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void updateEventWindow(@NonNull final EventWindow eventWindow) {
        // Any state older than this is unconditionally removed, even if it is the latest
        final long earliestPermittedRound =
                eventWindow.latestConsensusRound() - stateConfig.roundsToKeepForSigning() + 1;

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
