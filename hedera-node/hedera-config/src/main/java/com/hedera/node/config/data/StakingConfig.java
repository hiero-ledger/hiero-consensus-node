/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

@ConfigData("staking")
public record StakingConfig(
        @ConfigProperty(defaultValue = "1440") @NetworkProperty long periodMins,
        @ConfigProperty(value = "rewardHistory.numStoredPeriods", defaultValue = "365") @NetworkProperty
                int rewardHistoryNumStoredPeriods,
        // ConfigProperty(value = "startupHelper.recompute", defaultValue = "NODE_STAKES,PENDING_REWARDS")
        // Set<StakeStartupHelper.RecomputeType> startupHelperRecompute
        @ConfigProperty(value = "fees.nodeRewardPercentage", defaultValue = "10") @NetworkProperty
                int feesNodeRewardPercentage,
        @ConfigProperty(value = "fees.stakingRewardPercentage", defaultValue = "10") @NetworkProperty
                int feesStakingRewardPercentage,
        // @ConfigProperty(defaultValue = "") Map<Long, Long> nodeMaxToMinStakeRatios,
        @ConfigProperty(defaultValue = "true") @NetworkProperty boolean isEnabled,
        @ConfigProperty(defaultValue = "false") @NetworkProperty boolean requireMinStakeToReward,
        // Assume there should have been no skipped staking periods
        @ConfigProperty(defaultValue = "true") @NetworkProperty boolean assumeContiguousPeriods,
        // Can be renamed to just "rewardRate" when the "staking.rewardRate" property is removed
        // from all production 0.0.121 system files
        @ConfigProperty(defaultValue = "6849") @NetworkProperty long perHbarRewardRate,
        @ConfigProperty(defaultValue = "25000000000000000") @NetworkProperty long startThreshold,
        @ConfigProperty(defaultValue = "8500000000000000") @NetworkProperty long rewardBalanceThreshold,
        @ConfigProperty(defaultValue = "650000000000000000") @NetworkProperty long maxStakeRewarded,
        @ConfigProperty(defaultValue = "0") @NetworkProperty long minStake,
        @ConfigProperty(defaultValue = "45000000000000000") @NetworkProperty long maxStake) {}
