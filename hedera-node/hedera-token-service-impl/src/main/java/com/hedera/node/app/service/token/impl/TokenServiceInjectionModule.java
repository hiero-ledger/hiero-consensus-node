// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.token.CryptoSignatureWaivers;
import com.hedera.node.app.service.token.DenominationConverter;
import com.hedera.node.app.service.token.impl.handlers.staking.StakeRewardCalculator;
import com.hedera.node.app.service.token.impl.handlers.staking.StakeRewardCalculatorImpl;
import com.hedera.node.app.service.token.impl.handlers.staking.StakingRewardsHandler;
import com.hedera.node.app.service.token.impl.handlers.staking.StakingRewardsHandlerImpl;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.NativeCoinConfig;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Singleton;

/**
 * Dagger module of the token service.
 */
@Module
public interface TokenServiceInjectionModule {
    /**
     * Binds the {@link CryptoSignatureWaivers} to the {@link CryptoSignatureWaiversImpl}.
     * @param impl the implementation of the {@link CryptoSignatureWaivers}
     * @return the bound implementation
     */
    @Binds
    CryptoSignatureWaivers cryptoSignatureWaivers(CryptoSignatureWaiversImpl impl);

    /**
     * Binds the {@link StakingRewardsHandler} to the {@link StakingRewardsHandlerImpl}.
     * @param stakingRewardsHandler the implementation of the {@link StakingRewardsHandler}
     * @return the bound implementation
     */
    @Binds
    StakingRewardsHandler stakingRewardHandler(StakingRewardsHandlerImpl stakingRewardsHandler);
    /**
     * Binds the {@link StakeRewardCalculator} to the {@link StakeRewardCalculatorImpl}.
     * @param rewardCalculator the implementation of the {@link StakeRewardCalculator}
     * @return the bound implementation
     */
    @Binds
    StakeRewardCalculator stakeRewardCalculator(StakeRewardCalculatorImpl rewardCalculator);

    /**
     * Provides the {@link DenominationConverter} derived from the native coin decimals configuration.
     * @param configProvider the configuration provider
     * @return the denomination converter
     */
    @Provides
    @Singleton
    static DenominationConverter provideDenominationConverter(@NonNull final ConfigProvider configProvider) {
        requireNonNull(configProvider);
        final var decimals = configProvider
                .getConfiguration()
                .getConfigData(NativeCoinConfig.class)
                .decimals();
        return new DenominationConverter(decimals);
    }
}
