// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers.staking;

import static com.hedera.node.app.service.token.impl.TokenServiceImpl.HBARS_TO_TINYBARS;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Fraction;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.token.NetworkStakingRewards;
import com.hedera.hapi.node.state.token.StakingNodeInfo;
import com.hedera.hapi.node.transaction.NodeStake;
import com.hedera.hapi.node.transaction.NodeStakeUpdateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.ReadableNetworkStakingRewardsStore;
import com.hedera.node.config.data.StakingConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * This class includes utility methods for dealing with staking period calculations (the updates to
 * staking performed at the end of each staking period). That said, <b>these methods should NOT
 * update any state</b> (e.g. by passing in a writable store)
 */
public final class EndOfStakingPeriodUtils {
    private EndOfStakingPeriodUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }

    /**
     * Creates a human-readable string summary of the given reward sums history, omitting any trailing zeros.
     *
     * @param rewardSumHistory the rewards sum history to summarize
     * @return the summary
     */
    public static String readableNonZeroHistory(@NonNull final List<Long> rewardSumHistory) {
        int firstZero = -1;
        for (int i = 0; i < rewardSumHistory.size(); i++) {
            if (rewardSumHistory.get(i) == 0) {
                firstZero = i;
                break;
            }
        }
        return (firstZero == -1)
                ? rewardSumHistory.toString()
                : IntStream.range(0, firstZero)
                        .mapToObj(rewardSumHistory::get)
                        .toList()
                        .toString();
    }

    /**
     * Returns a {@link NodeStake} object from the given {@link StakingNodeInfo} object and latest reward rate
     * pre-calculated for convenience.
     * @param rewardRate the latest reward rate
     * @param stakingNodeInfo the staking node info
     * @return the {@link NodeStake} object
     */
    public static NodeStake fromStakingInfo(final long rewardRate, @NonNull final StakingNodeInfo stakingNodeInfo) {
        requireNonNull(stakingNodeInfo);
        return NodeStake.newBuilder()
                .nodeId(stakingNodeInfo.nodeNumber())
                .stake(stakingNodeInfo.stake())
                .rewardRate(rewardRate)
                .minStake(stakingNodeInfo.minStake())
                .maxStake(stakingNodeInfo.maxStake())
                .stakeRewarded(stakingNodeInfo.stakeToReward())
                .stakeNotRewarded(stakingNodeInfo.stakeToNotReward())
                .build();
    }

    /**
     * Given a {@link ReadableNetworkStakingRewardsStore} instance, returns a new {@link NetworkStakingRewards.Builder}
     * instance with the same values as the given store.
     * @param networkRewardsStore the store to copy values from
     * @return the new builder instance
     */
    public static NetworkStakingRewards.Builder asStakingRewardBuilder(
            @NonNull final ReadableNetworkStakingRewardsStore networkRewardsStore) {
        requireNonNull(networkRewardsStore);
        return networkRewardsStore
                .get()
                .copyBuilder()
                .pendingRewards(networkRewardsStore.pendingRewards())
                .stakingRewardsActivated(networkRewardsStore.isStakingRewardsActivated())
                .totalStakedRewardStart(networkRewardsStore.totalStakeRewardStart())
                .totalStakedStart(networkRewardsStore.totalStakedStart());
    }

    /**
     * Given information about node stakes and staking reward rates for an ending period, initializes a
     * transaction builder with a {@link NodeStakeUpdateTransactionBody} that summarizes this information.
     *
     * @param stakingPeriodEnd the last nanosecond of the staking period being described
     * @param nodeStakes the stakes of each node at the end of the just-ending period
     * @param stakingConfig the staking configuration of the network at period end
     * @param totalStakedRewardStart the total staked reward at the start of the period
     * @param maxPerHbarRewardRate the maximum reward rate per hbar for the period (per HIP-782)
     * @param reservedStakingRewards the total amount of staking rewards reserved in the 0.0.800 balance
     * @param unreservedStakingRewardBalance the remaining "unreserved" part of the 0.0.800 balance
     * @param rewardBalanceThreshold the 0.0.800 balance threshold at which the max reward rate is attainable
     * @param maxStakeRewarded the maximum stake that can be rewarded at the max reward rate
     * @param memo the memo to include in the transaction
     * @return the transaction builder with the {@code NodeStakeUpdateTransactionBody} set
     */
    public static TransactionBody.Builder newNodeStakeUpdateBuilder(
            @NonNull final Timestamp stakingPeriodEnd,
            @NonNull final List<NodeStake> nodeStakes,
            @NonNull final StakingConfig stakingConfig,
            final long totalStakedRewardStart,
            final long maxPerHbarRewardRate,
            final long reservedStakingRewards,
            final long unreservedStakingRewardBalance,
            final long rewardBalanceThreshold,
            final long maxStakeRewarded,
            @NonNull final String memo) {
        requireNonNull(stakingPeriodEnd);
        requireNonNull(nodeStakes);
        requireNonNull(stakingConfig);
        requireNonNull(memo);
        final var threshold = stakingConfig.startThreshold();
        final var stakingPeriod = stakingConfig.periodMins();
        final var stakingPeriodsStored = stakingConfig.rewardHistoryNumStoredPeriods();

        final var nodeRewardFeeFraction = Fraction.newBuilder()
                .numerator(stakingConfig.feesNodeRewardPercentage())
                .denominator(100L)
                .build();
        final var stakingRewardFeeFraction = Fraction.newBuilder()
                .numerator(stakingConfig.feesStakingRewardPercentage())
                .denominator(100L)
                .build();

        final var hbarsStakedToReward = (totalStakedRewardStart / HBARS_TO_TINYBARS);
        final var maxTotalReward = maxPerHbarRewardRate * hbarsStakedToReward;
        final var txnBody = NodeStakeUpdateTransactionBody.newBuilder()
                .endOfStakingPeriod(stakingPeriodEnd)
                .nodeStake(nodeStakes)
                .maxStakingRewardRatePerHbar(maxPerHbarRewardRate)
                .nodeRewardFeeFraction(nodeRewardFeeFraction)
                .stakingPeriodsStored(stakingPeriodsStored)
                .stakingPeriod(stakingPeriod)
                .stakingRewardFeeFraction(stakingRewardFeeFraction)
                .stakingStartThreshold(threshold)
                // Deprecated field but keep it for backward compatibility at the moment
                .stakingRewardRate(maxTotalReward)
                .maxTotalReward(maxTotalReward)
                .reservedStakingRewards(reservedStakingRewards)
                .unreservedStakingRewardBalance(unreservedStakingRewardBalance)
                .rewardBalanceThreshold(rewardBalanceThreshold)
                .maxStakeRewarded(maxStakeRewarded)
                .build();

        return TransactionBody.newBuilder().memo(memo).nodeStakeUpdate(txnBody);
    }

    /**
     * Returns the timestamp that is just before midnight of the day of the given consensus time.
     *
     * @param consensusTime the consensus time
     * @return the timestamp that is just before midnight of the day of the given consensus time
     */
    public static Timestamp lastInstantOfPreviousPeriodFor(@NonNull final Instant consensusTime) {
        final var justBeforeMidNightTime = LocalDate.ofInstant(consensusTime, ZoneId.of("UTC"))
                .atStartOfDay()
                .minusNanos(1); // give out the timestamp that is just before midnight
        return Timestamp.newBuilder()
                .seconds(justBeforeMidNightTime.toEpochSecond(ZoneOffset.UTC))
                .nanos(justBeforeMidNightTime.getNano())
                .build();
    }

    /**
     * Stores both the new reward sum history and the new per-hbar reward rate for a node.
     */
    public record RewardSumHistory(List<Long> rewardSumHistory, long pendingRewardRate) {}

    /**
     * Calculates a reward sum history for a node based on the node's past reward sums.
     * <p>
     * <b>NOTE: this method does not alter any state!</b> It merely performs the calculation and returns the result
     *
     * @param currentInfo the node's current staking info
     * @param perHbarRate the current per-hbar reward rate for this node
     * @param maxPerHbarRate the maximum per-hbar reward rate for this node
     * @param requireMinStakeToReward if true, will require the node's stake to meet a threshold
     *                                in order to receive rewards
     * @return the calculated {@link RewardSumHistory}
     */
    public static RewardSumHistory computeExtendedRewardSumHistory(
            @NonNull final StakingNodeInfo currentInfo,
            final long perHbarRate,
            final long maxPerHbarRate,
            final boolean requireMinStakeToReward) {
        final var currRewardSumHistory = currentInfo.rewardSumHistory();
        final var newRewardSumHistory = new ArrayList<>(currRewardSumHistory);
        final var droppedRewardSum = currRewardSumHistory.getLast();
        for (int i = currRewardSumHistory.size() - 1; i > 0; i--) {
            newRewardSumHistory.set(i, currRewardSumHistory.get(i - 1) - droppedRewardSum);
        }
        newRewardSumHistory.set(0, currRewardSumHistory.getFirst() - droppedRewardSum);

        long perHbarRateThisNode = 0;
        // If this node was "active"---i.e., node.numRoundsWithJudge / numRoundsInPeriod >= activeThreshold---and it had
        // non-zero stakedReward at the start of the ending staking period, then it should give rewards for this staking
        // period, unless its effective stake was less than minStake, and hence zero here (note the active condition
        // will only be checked in a later release)
        final var rewardableStake = requireMinStakeToReward
                ? Math.min(currentInfo.stakeRewardStart(), currentInfo.stake())
                : currentInfo.stakeRewardStart();
        if (rewardableStake > 0) {
            perHbarRateThisNode = perHbarRate;
            // But if the node had more the maximum stakeRewardStart, "down-scale" its reward rate to ensure the
            // accounts staking to this node will receive a fraction of the total rewards that does not exceed
            // node.stakedRewardStart / totalStakedRewardedStart; use arbitrary-precision arithmetic because there is no
            // inherent bound on (maxStake * pendingRewardRate)
            if (currentInfo.stakeRewardStart() > currentInfo.maxStake()) {
                perHbarRateThisNode = BigInteger.valueOf(perHbarRateThisNode)
                        .multiply(BigInteger.valueOf(currentInfo.maxStake()))
                        .divide(BigInteger.valueOf(currentInfo.stakeRewardStart()))
                        .longValueExact();
            }
        }
        perHbarRateThisNode = Math.min(perHbarRateThisNode, maxPerHbarRate);
        newRewardSumHistory.set(0, newRewardSumHistory.getFirst() + perHbarRateThisNode);
        return new RewardSumHistory(newRewardSumHistory, perHbarRateThisNode);
    }

    /**
     * Stores both the new stake and the new stakeRewardStart for a node.
     */
    public record StakeResult(long stake, long stakeRewardStart) {}

    /**
     * Computes a clamped stake value between [minStake, maxStake] for a node based on the node's
     * current staking info. The new {@code stakeRewardStart} value is also computed
     *
     * @param stakingInfo the node's current staking info
     * @param stakingConfig the staking configuration of the network
     * @return the calculated {@link StakeResult}
     */
    @NonNull
    public static StakeResult computeNewStakes(
            @NonNull final StakingNodeInfo stakingInfo, @NonNull final StakingConfig stakingConfig) {
        requireNonNull(stakingInfo);
        requireNonNull(stakingConfig);
        final var totalStake = stakingInfo.stakeToReward() + stakingInfo.stakeToNotReward();
        final long newStake;
        final long effectiveMax = Math.min(stakingInfo.maxStake(), stakingConfig.maxStake());
        final long effectiveMin = Math.min(effectiveMax, Math.max(stakingInfo.minStake(), stakingConfig.minStake()));
        if (totalStake > effectiveMax) {
            newStake = effectiveMax;
        } else if (totalStake < effectiveMin) {
            newStake = 0;
        } else {
            newStake = totalStake;
        }
        return new StakeResult(newStake, stakingInfo.stakeToReward());
    }
}
