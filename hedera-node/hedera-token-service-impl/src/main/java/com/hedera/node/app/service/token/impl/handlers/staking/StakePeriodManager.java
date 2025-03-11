// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers.staking;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.token.ReadableNetworkStakingRewardsStore;
import com.hedera.node.app.service.token.api.StakingRewardsApi;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.StakingConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneId;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class manages the current stake period and the previous stake period.
 */
@Singleton
public class StakePeriodManager {
    /**
     * The UTC time zone.
     */
    public static final ZoneId ZONE_UTC = ZoneId.of("UTC");
    /**
     * The default staking period in minutes.
     */
    public static final long DEFAULT_STAKING_PERIOD_MINS = 1440L;

    private final int numStoredPeriods;
    private final long stakingPeriodMins;
    private final InstantSource instantSource;

    private long currentStakePeriod;
    private long prevConsensusSecs;

    /**
     * Default constructor for injection.
     * @param configProvider the configuration provider
     */
    @Inject
    public StakePeriodManager(
            @NonNull final ConfigProvider configProvider, @NonNull final InstantSource instantSource) {
        final var config = configProvider.getConfiguration().getConfigData(StakingConfig.class);
        numStoredPeriods = config.rewardHistoryNumStoredPeriods();
        stakingPeriodMins = config.periodMins();
        this.instantSource = Objects.requireNonNull(instantSource);
    }

    /**
     * Returns the epoch second at the start of the given stake period. It is used in
     * {@link StakeRewardCalculatorImpl} to set the stakePeriodStart
     * on each {@link com.hedera.hapi.node.state.token.StakingNodeInfo} object
     * @param stakePeriod the stake period
     * @return the epoch second at the start of the given stake period
     */
    public long epochSecondAtStartOfPeriod(final long stakePeriod) {
        return StakingRewardsApi.epochSecondAtStartOfPeriod(stakePeriod, stakingPeriodMins);
    }

    /**
     * Sets the current stake period, based on the current consensus time. Avoids excessive
     * stake period calculations by only updating when the consensus second has changed from
     * the last time the method was called.
     * @param consensusNow the current consensus time
     */
    public void setCurrentStakePeriodFor(@NonNull final Instant consensusNow) {
        final var currentConsensusSecs = consensusNow.getEpochSecond();
        if (prevConsensusSecs != currentConsensusSecs) {
            currentStakePeriod = StakingRewardsApi.stakePeriodAt(consensusNow, stakingPeriodMins);
            prevConsensusSecs = currentConsensusSecs;
        }
    }

    /**
     * Returns the current stake period, based on the current consensus time.
     * Current staking period is very important to calculate rewards.
     * Since any account is rewarded only once per a stake period.
     * @return the current stake period
     */
    public long currentStakePeriod() {
        return currentStakePeriod;
    }

    /**
     * Based on the stake period start, returns if the current consensus time is
     * rewardable or not.
     * @param stakePeriodStart the stake period start
     * @param networkRewards the network rewards
     * @return true if the current consensus time is rewardable, false otherwise
     */
    public boolean isRewardable(
            final long stakePeriodStart, @NonNull final ReadableNetworkStakingRewardsStore networkRewards) {
        return stakePeriodStart > -1 && stakePeriodStart < firstNonRewardableStakePeriod(networkRewards);
    }

    /**
     * Returns the first stake period that is not rewardable. This is used to determine
     * if an account is eligible for a reward, as soon as staking rewards are activated.
     * @param rewardsStore the network rewards store
     * @return the first stake period that is not rewardable
     */
    public long firstNonRewardableStakePeriod(@NonNull final ReadableNetworkStakingRewardsStore rewardsStore) {

        // The earliest period by which an account can have started staking, _without_ becoming
        // eligible for a reward; if staking is not active, this will return Long.MIN_VALUE so
        // no account can ever be eligible.
        // Remember that accounts are only rewarded for _full_ periods.
        // So if Alice started staking in the previous period (current - 1), she will not have
        // completed a full period until current has ended
        return rewardsStore.isStakingRewardsActivated() ? currentStakePeriod() - 1 : Long.MIN_VALUE;
    }

    /**
     * Returns the effective stake period start, based on the current stake period and the
     * number of stored periods.
     * @param stakePeriodStart the stake period start
     * @return the effective stake period start
     */
    public long effectivePeriod(final long stakePeriodStart) {
        return StakingRewardsApi.clampedStakePeriodStart(stakePeriodStart, currentStakePeriod, numStoredPeriods);
    }

    /* ----------------------- estimated stake periods ----------------------- */
    /**
     * Returns the estimated current stake period, based on the current wall-clock time.
     * We use wall-clock time here, because this method is called in two places:
     * 1. When we get the first stakePeriod after staking rewards are activated, to see
     *    if any rewards can be triggered.
     * 2. When we do upgrade, if we need to migrate any staking rewards.
     * The default staking period is 1 day, so this will return the current day.
     * For testing we use a shorter staking period, so we can estimate staking period for
     * a shorter period.
     * @return the estimated current stake period
     */
    public long estimatedCurrentStakePeriod() {
        return StakingRewardsApi.estimatedCurrentStakePeriod(stakingPeriodMins, instantSource.instant());
    }

    /**
     * Returns the estimated first stake period that is not rewardable.
     * @param networkRewards the network rewards
     * @return the estimated first stake period that is not rewardable
     */
    public long estimatedFirstNonRewardableStakePeriod(
            @NonNull final ReadableNetworkStakingRewardsStore networkRewards) {
        return networkRewards.isStakingRewardsActivated() ? estimatedCurrentStakePeriod() - 1 : Long.MIN_VALUE;
    }

    /**
     * Returns if the estimated stake period is rewardable or not.
     * @param stakePeriodStart the stake period start
     * @param networkRewards the network rewards
     * @return true if the estimated stake period is rewardable, false otherwise
     */
    public boolean isEstimatedRewardable(
            final long stakePeriodStart, @NonNull final ReadableNetworkStakingRewardsStore networkRewards) {
        return StakingRewardsApi.isEstimatedRewardable(
                stakingPeriodMins,
                stakePeriodStart,
                networkRewards.isStakingRewardsActivated(),
                instantSource.instant());
    }

    /**
     * Given the current and new staked ids for an account, as well as if it received a reward in
     * this transaction, returns the new {@code stakePeriodStart} for this account:
     *
     * <ol>
     *   <li>{@code -1} if the {@code stakePeriodStart} doesn't need to change; or,
     *   <li>The value to which the {@code stakePeriodStart} should be changed.
     * </ol>.
     *
     * @param originalAccount the original account before the transaction
     * @param modifiedAccount the modified account after the transaction
     * @param rewarded whether the account was rewarded during the transaction
     * @param stakeMetaChanged whether the account's stake metadata changed
     * @return either NA for no new stakePeriodStart, or the new value
     */
    public long startUpdateFor(
            @Nullable final Account originalAccount,
            @NonNull final Account modifiedAccount,
            final boolean rewarded,
            final boolean stakeMetaChanged) {
        // Only worthwhile to update stakedPeriodStart for an account staking to a node
        if (modifiedAccount.hasStakedNodeId()) {
            if ((originalAccount != null && originalAccount.hasStakedAccountId()) || stakeMetaChanged) {
                // We just started staking to a node today
                return currentStakePeriod();
            } else if (rewarded) {
                // If we were just rewarded, stake period start is yesterday
                return currentStakePeriod() - 1;
            }
        }
        return -1;
    }

    /**
     * Returns the consensus time of previous transaction, that is used to change the current stake period.
     * @return the consensus time of previous transaction
     */
    @VisibleForTesting
    public long getPrevConsensusSecs() {
        return prevConsensusSecs;
    }
}
