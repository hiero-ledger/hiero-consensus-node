// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl;

import com.hedera.node.app.service.token.CryptoSignatureWaivers;
import com.hedera.node.app.service.token.impl.handlers.staking.StakeRewardCalculator;
import com.hedera.node.app.service.token.impl.handlers.staking.StakeRewardCalculatorImpl;
import com.hedera.node.app.service.token.impl.handlers.staking.StakingRewardsHandler;
import com.hedera.node.app.service.token.impl.handlers.staking.StakingRewardsHandlerImpl;
import dagger.Binds;
import dagger.Module;

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
}
