// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.services;

import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.ReadableStakePeriodInfoStoreImpl;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.StakingConfig;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Instant;

import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.node.app.services.StakePeriodInfoManager.LastStakePeriodCalculationsTime.CURRENT_PERIOD;
import static com.hedera.node.app.workflows.handle.steps.StakePeriodChanges.isNextStakingPeriod;

/**
 * Manages the StakePeriodInfo singleton.
 */
@Singleton
public class StakePeriodInfoManager {
    private static final Logger log = LogManager.getLogger(StakePeriodInfoManager.class);

    private final ConfigProvider configProvider;

    /**
     * Constructs an {@link StakePeriodInfoManager} instance.
     *
     * @param configProvider the configuration provider
     */
    @Inject
    public StakePeriodInfoManager(
            @NonNull final ConfigProvider configProvider) {
        this.configProvider = configProvider;
    }

    /**
     * The possible times at which the last time node fees were distributed
     */
    enum LastStakePeriodCalculationsTime {
        /**
         * Node fees have never been distributed. In the genesis edge case, we don't need to distribute fees.
         */
        NEVER,
        /**
         * The last time node fees were distributed was in the previous staking period.
         */
        PREVIOUS_PERIOD,
        /**
         * The last time node fees were distributed was in the current staking period.
         */
        CURRENT_PERIOD,
    }

    /**
     * Checks if the last time stake period calculations were done was a different staking period.
     *
     * @param state the state
     * @param now the current time
     * @return whether the last time stake period calculations were done was a different staking period
     */
    LastStakePeriodCalculationsTime classifyLastStakePeriodCalculationTime(
            @NonNull final State state, @NonNull final Instant now) {
        final var stakePeriodInfoStore = new ReadableStakePeriodInfoStoreImpl(state.getReadableStates(TokenService.NAME));
        final var lastCalculationTime = stakePeriodInfoStore.get().lastStakePeriodCalculationTime();
        if (lastCalculationTime == null) {
            return LastStakePeriodCalculationsTime.NEVER;
        }
        final long stakePeriodMins = configProvider
                .getConfiguration()
                .getConfigData(StakingConfig.class)
                .periodMins();
        final boolean isNextPeriod = isNextStakingPeriod(now, asInstant(lastCalculationTime), stakePeriodMins);
        return isNextPeriod ? LastStakePeriodCalculationsTime.PREVIOUS_PERIOD : CURRENT_PERIOD;
    }
}
