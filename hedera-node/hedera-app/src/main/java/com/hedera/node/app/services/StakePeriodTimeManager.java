// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.services;

import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.service.token.impl.schemas.V0710TokenSchema.STAKE_PERIOD_UPDATED_TIME_STATE_ID;
import static com.hedera.node.app.workflows.handle.steps.StakePeriodChanges.isNextStakingPeriod;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.token.StakePeriodUpdatedTime;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.StakingConfig;
import com.swirlds.state.State;
import com.swirlds.state.spi.CommittableWritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Manages the stake period time state and provides a centralized way to determine if a new staking period
 * has started. This consolidates the stake period classification logic that was previously duplicated
 * across NodeFeeManager and NodeRewardManager.
 *
 * <p>The stake period is tracked using a singleton state that stores the consensus time when stake
 * period updates were last performed. This allows all stake period-related operations to use a
 * consistent reference point.
 *
 * <p>Note: This class is different from {@link com.hedera.node.app.service.token.impl.handlers.staking.StakePeriodManager}
 * which manages the current stake period for staking rewards calculations.
 */
@Singleton
public class StakePeriodTimeManager {
    private static final Logger log = LogManager.getLogger(StakePeriodTimeManager.class);

    private final ConfigProvider configProvider;

    /**
     * The possible classifications for the last stake period update time.
     */
    public enum LastStakePeriodUpdateTime {
        /**
         * Stake period updates have never been performed. This is the genesis edge case.
         */
        NEVER,
        /**
         * The last stake period update was in a previous staking period.
         */
        PREVIOUS_PERIOD,
        /**
         * The last stake period update was in the current staking period.
         */
        CURRENT_PERIOD,
    }

    /**
     * Constructs a StakePeriodTimeManager instance.
     *
     * @param configProvider the configuration provider
     */
    @Inject
    public StakePeriodTimeManager(@NonNull final ConfigProvider configProvider) {
        this.configProvider = requireNonNull(configProvider);
    }

    /**
     * Classifies the last stake period update time relative to the given consensus time.
     *
     * @param state the state
     * @param now the current consensus time
     * @return the classification of the last stake period update time
     */
    public LastStakePeriodUpdateTime classifyLastStakePeriodUpdateTime(
            @NonNull final State state, @NonNull final Instant now) {
        requireNonNull(state);
        requireNonNull(now);

        final var stakePeriodUpdatedTimeState = state.getReadableStates(TokenService.NAME)
                .<StakePeriodUpdatedTime>getSingleton(STAKE_PERIOD_UPDATED_TIME_STATE_ID);
        final var stakePeriodUpdatedTime = requireNonNull(stakePeriodUpdatedTimeState.get());
        final var lastUpdateTime = stakePeriodUpdatedTime.lastStakePeriodUpdateTime();

        if (lastUpdateTime == null) {
            return LastStakePeriodUpdateTime.NEVER;
        }

        final long stakePeriodMins = configProvider
                .getConfiguration()
                .getConfigData(StakingConfig.class)
                .periodMins();
        final boolean isNextPeriod = isNextStakingPeriod(now, asInstant(lastUpdateTime), stakePeriodMins);
        return isNextPeriod ? LastStakePeriodUpdateTime.PREVIOUS_PERIOD : LastStakePeriodUpdateTime.CURRENT_PERIOD;
    }

    /**
     * Checks if a new staking period has started based on the given consensus time.
     *
     * @param state the state
     * @param now the current consensus time
     * @return true if a new staking period has started, false otherwise
     */
    public boolean isNewStakingPeriod(@NonNull final State state, @NonNull final Instant now) {
        final var classification = classifyLastStakePeriodUpdateTime(state, now);
        return classification != LastStakePeriodUpdateTime.CURRENT_PERIOD;
    }

    /**
     * Updates the stake period updated time in state to the given consensus time.
     * This should be called after all stake period changes have been processed.
     *
     * @param state the state
     * @param now the current consensus time
     */
    public void updateStakePeriodTime(@NonNull final State state, @NonNull final Instant now) {
        requireNonNull(state);
        requireNonNull(now);

        final var writableStates = state.getWritableStates(TokenService.NAME);
        final var stakePeriodUpdatedTimeState =
                writableStates.<StakePeriodUpdatedTime>getSingleton(STAKE_PERIOD_UPDATED_TIME_STATE_ID);

        stakePeriodUpdatedTimeState.put(StakePeriodUpdatedTime.newBuilder()
                .lastStakePeriodUpdateTime(asTimestamp(now))
                .build());

        ((CommittableWritableStates) writableStates).commit();
        log.info("Updated stake period time to {}", asTimestamp(now));
    }
}

