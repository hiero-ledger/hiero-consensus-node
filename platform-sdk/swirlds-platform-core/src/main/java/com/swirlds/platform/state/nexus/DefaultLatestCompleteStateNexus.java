// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.nexus;

import static com.swirlds.logging.legacy.LogMarker.STATE_TO_DISK;
import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.config.StateConfig;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.snapshot.SignedStateFileWriter;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.StackTrace;
import org.hiero.consensus.metrics.RunningAverageMetric;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.model.hashgraph.EventWindow;

/**
 * The default implementation of {@link LatestCompleteStateNexus}.
 */
public class DefaultLatestCompleteStateNexus implements LatestCompleteStateNexus {

    private static final Logger logger = LogManager.getLogger(DefaultLatestCompleteStateNexus.class);

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
        logger.info(STATE_TO_DISK.getMarker(), "Call to setState()");// {}", StackTrace.getStackTrace());

        if (currentState != null) {
            logger.info(STATE_TO_DISK.getMarker(), "setState(): Closing current state with round={}, reservation reason={}, state to disk reason={}, isFreeze={}",
                    currentState.get().getRound(), currentState.reason, currentState.get().getStateToDiskReason(), currentState.get().isFreezeState());
            currentState.close();
        } else {
            logger.info(STATE_TO_DISK.getMarker(), "setState(): Current state is null!");
        }

        if (reservedSignedState == null) {
            logger.info(STATE_TO_DISK.getMarker(), "setState(): Setting current res signed state to null");
        } else if (reservedSignedState.getNullable() == null) {
            logger.info(STATE_TO_DISK.getMarker(), "setState(): Setting current state to the res signed state with null state, reservation reason={}", reservedSignedState.reason);

        } else {
            logger.info(STATE_TO_DISK.getMarker(), "setState(): Setting current state to the state with round={}, reservation reason={}, state to disk reason={}, isFreeze={}",
                    reservedSignedState.get().getRound(), reservedSignedState.reason, reservedSignedState.get().getStateToDiskReason(), reservedSignedState.get().isFreezeState());
        }

        currentState = reservedSignedState;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void setStateIfNewer(@NonNull final ReservedSignedState reservedSignedState) {
        logger.info(STATE_TO_DISK.getMarker(), "Call to setStateIfNewer()");// {}", StackTrace.getStackTrace());
        logger.info(STATE_TO_DISK.getMarker(), "setStateIfNewer(): reserved state is not null={}, current state round={}, reserved state round={}",
                reservedSignedState.isNotNull(), getRound(), reservedSignedState.get().getRound());

        if (reservedSignedState.isNotNull()
                && getRound() < reservedSignedState.get().getRound()) {
            logger.info(STATE_TO_DISK.getMarker(), "setStateIfNewer(): updating the state, see next log messages...");
            setState(reservedSignedState);
        } else {
            logger.info(STATE_TO_DISK.getMarker(), "setStateIfNewer(): reserved state is not newer than current state, closing reserved state");
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
        logger.info(STATE_TO_DISK.getMarker(), "Call to getState()");// {}", StackTrace.getStackTrace());
        if (currentState == null) {
            logger.info(STATE_TO_DISK.getMarker(), "getState(): current state is null!");
            return null;
        }
        logger.info(STATE_TO_DISK.getMarker(), "getState(): returning state with round={}, reason={}, state to disk reason={}, isFreeze={} AND reserving it for reason={}",
                currentState.get().getRound(), currentState.reason, currentState.get().getStateToDiskReason(), currentState.get().isFreezeState(), reason);
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
