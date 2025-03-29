// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.services;

import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.hapi.fees.pricing.FeeSchedules.USD_TO_TINYCENTS;
import static com.hedera.node.app.service.token.impl.schemas.V0610TokenSchema.NODE_REWARDS_KEY;
import static com.hedera.node.app.workflows.handle.steps.StakePeriodChanges.isNextStakingPeriod;
import static com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema.PLATFORM_STATE_KEY;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toCollection;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.node.state.token.NodeActivity;
import com.hedera.hapi.node.state.token.NodeRewards;
import com.hedera.hapi.platform.state.JudgeId;
import com.hedera.hapi.platform.state.PlatformState;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.ids.ReadableEntityIdStoreImpl;
import com.hedera.node.app.roster.RosterService;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.ReadableAccountStoreImpl;
import com.hedera.node.app.service.token.impl.ReadableNetworkStakingRewardsStoreImpl;
import com.hedera.node.app.service.token.impl.WritableNetworkStakingRewardsStore;
import com.hedera.node.app.service.token.impl.WritableNodeRewardsStoreImpl;
import com.hedera.node.app.workflows.handle.record.SystemTransactions;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.NodesConfig;
import com.hedera.node.config.data.StakingConfig;
import com.swirlds.platform.state.service.PlatformStateService;
import com.swirlds.platform.state.service.ReadableRosterStoreImpl;
import com.swirlds.state.State;
import com.swirlds.state.lifecycle.EntityIdFactory;
import com.swirlds.state.spi.CommittableWritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Manages the node rewards for the network. This includes tracking the number of rounds in the current staking
 * period, the number of missed judge rounds for each node, and the fees collected by all nodes in a staking period.
 * This class is responsible for updating the node rewards state at the end of each block and for paying
 * rewards to active nodes at the end of each staking period.
 */
@Singleton
public class NodeRewardManager {
    private final ConfigProvider configProvider;
    private final EntityIdFactory entityIdFactory;
    private final ExchangeRateManager exchangeRateManager;

    // The number of rounds so far in the staking period
    private long roundsThisStakingPeriod = 0;
    // The number of rounds each node missed creating judge. This is updated from state at the start of every round
    // and will be written back to state at the end of every block
    private final SortedMap<Long, Long> missedJudgeCounts = new TreeMap<>();

    @Inject
    public NodeRewardManager(
            @NonNull final ConfigProvider configProvider,
            @NonNull final EntityIdFactory entityIdFactory,
            @NonNull final ExchangeRateManager exchangeRateManager) {
        this.configProvider = requireNonNull(configProvider);
        this.entityIdFactory = requireNonNull(entityIdFactory);
        this.exchangeRateManager = requireNonNull(exchangeRateManager);
    }

    public void onOpenBlock(@NonNull final State state) {
        // read the node rewards info from state at start of every block. So, we can commit the accumulated changes
        // at end of every block
        if (configProvider.getConfiguration().getConfigData(NodesConfig.class).nodeRewardsEnabled()) {
            missedJudgeCounts.clear();
            final var nodeRewardInfo = nodeRewardInfoFrom(state);
            roundsThisStakingPeriod = nodeRewardInfo.numRoundsInStakingPeriod();
            nodeRewardInfo
                    .nodeActivities()
                    .forEach(activity -> missedJudgeCounts.put(activity.nodeId(), activity.numMissedJudgeRounds()));
        }
    }

    /**
     * Updates node rewards state at the end of a block given the collected node fees.
     * @param state the state
     * @param nodeFeesCollected the fees collected into node accounts in the block
     */
    public void onCloseBlock(@NonNull final State state, final long nodeFeesCollected) {
        // If node rewards are enabled, we need to update the node rewards state with the current round and missed
        // judge counts
        if (configProvider.getConfiguration().getConfigData(NodesConfig.class).nodeRewardsEnabled()) {
            updateNodeRewardState(state, nodeFeesCollected);
        }
    }

    /**
     * Updates the number of rounds in the current staking period and the number of missed judge rounds for each node.
     * This method is called at the end of each round.
     *
     * @param state the state
     */
    public void updateJudgesOnEndRound(State state) {
        // Track missing judges in this round
        missingJudgesInLastRoundOf(state).forEach(nodeId -> missedJudgeCounts.merge(nodeId, 1L, Long::sum));
        roundsThisStakingPeriod++;
    }

    /**
     * Resets the node rewards state for the next staking period. This method is called at the end of each
     * staking period irrespective of whether node rewards are paid.
     */
    public void resetNodeRewards() {
        missedJudgeCounts.clear();
        roundsThisStakingPeriod = 0;
    }

    /**
     * The possible times at which the last time node rewards were paid.
     */
    private enum LastNodeRewardsPaymentTime {
        /**
         * Node rewards have never been paid. In the genesis edge case, we don't need to pay rewards.
         */
        NEVER,
        /**
         * The last time node rewards were paid was in the previous staking period.
         */
        PREVIOUS_PERIOD,
        /**
         * The last time node rewards were paid was in the current staking period.
         */
        CURRENT_PERIOD,
    }

    /**
     * Checks if the last time node rewards were paid was a different staking period.
     *
     * @param state the state
     * @param now   the current time
     * @return whether the last time node rewards were paid was a different staking period
     */
    private LastNodeRewardsPaymentTime classifyLastNodeRewardsPaymentTime(
            @NonNull final State state, @NonNull final Instant now) {
        final var networkRewardsStore =
                new ReadableNetworkStakingRewardsStoreImpl(state.getReadableStates(TokenService.NAME));
        final var lastPaidTime = networkRewardsStore.get().lastNodeRewardPaymentsTime();
        if (lastPaidTime == null) {
            return LastNodeRewardsPaymentTime.NEVER;
        }
        final long stakePeriodMins = configProvider
                .getConfiguration()
                .getConfigData(StakingConfig.class)
                .periodMins();
        final boolean isNextPeriod = isNextStakingPeriod(now, asInstant(lastPaidTime), stakePeriodMins);
        return isNextPeriod ? LastNodeRewardsPaymentTime.PREVIOUS_PERIOD : LastNodeRewardsPaymentTime.CURRENT_PERIOD;
    }

    /**
     * If the consensus time just crossed a stake period, rewards sufficiently active nodes for the previous period.
     *
     * @param state              the state
     * @param now                the current consensus time
     * @param systemTransactions the system transactions
     */
    public void maybeRewardActiveNodes(
            @NonNull final State state, @NonNull final Instant now, final SystemTransactions systemTransactions) {
        final var config = configProvider.getConfiguration();
        final var nodesConfig = config.getConfigData(NodesConfig.class);
        if (!nodesConfig.nodeRewardsEnabled()) {
            return;
        }
        final var lastNodeRewardsPaymentTime = classifyLastNodeRewardsPaymentTime(state, now);
        // If we're in the same staking period as the last time node rewards were paid, we don't
        // need to do anything
        if (lastNodeRewardsPaymentTime == LastNodeRewardsPaymentTime.CURRENT_PERIOD) {
            return;
        }
        final var writableStates = state.getWritableStates(TokenService.NAME);
        final var nodeRewardStore = new WritableNodeRewardsStoreImpl(writableStates);
        // Don't try to pay rewards in the genesis edge case when LastNodeRewardsPaymentTime.NEVER
        if (lastNodeRewardsPaymentTime == LastNodeRewardsPaymentTime.PREVIOUS_PERIOD) {
            // Identify the nodes active in the last staking period
            final var rosterStore = new ReadableRosterStoreImpl(state.getReadableStates(RosterService.NAME));
            final var currentRoster =
                    requireNonNull(rosterStore.getActiveRoster()).rosterEntries();
            final var activeNodeIds =
                    nodeRewardStore.getActiveNodeIds(currentRoster, nodesConfig.activeRoundsPercent());

            // And pay whatever rewards the network can afford
            final var rewardsAccountId = entityIdFactory.newAccountId(
                    config.getConfigData(AccountsConfig.class).nodeRewardAccount());
            final var entityCounters = new ReadableEntityIdStoreImpl(state.getReadableStates(EntityIdService.NAME));
            final var accountStore = new ReadableAccountStoreImpl(writableStates, entityCounters);
            final long rewardAccountBalance = requireNonNull(accountStore.getAccountById(rewardsAccountId))
                    .tinybarBalance();
            final long prePaidRewards = nodesConfig.adjustNodeFees()
                    ? nodeRewardStore.get().nodeFeesCollected() / currentRoster.size()
                    : 0L;

            final var targetPayInTinyCents = BigInteger.valueOf(nodesConfig.targetYearlyNodeRewardsUsd())
                    .multiply(USD_TO_TINYCENTS.toBigInteger())
                    .divide(BigInteger.valueOf(nodesConfig.numPeriodsToTargetUsd()));
            final var minimumRewardInTinyCents = exchangeRateManager.getTinybarsFromTinyCents(
                    Math.max(
                            0L,
                            BigInteger.valueOf(nodesConfig.minPerPeriodNodeRewardUsd())
                                    .multiply(USD_TO_TINYCENTS.toBigInteger())
                                    .longValue()),
                    now);
            final long nodeReward = exchangeRateManager.getTinybarsFromTinyCents(targetPayInTinyCents.longValue(), now);
            final var perActiveNodeReward = Math.max(minimumRewardInTinyCents, nodeReward - prePaidRewards);

            systemTransactions.dispatchNodeRewards(
                    state,
                    now,
                    activeNodeIds,
                    perActiveNodeReward,
                    rewardsAccountId,
                    rewardAccountBalance,
                    minimumRewardInTinyCents,
                    rosterStore.getActiveRoster().rosterEntries());
        }
        // Record this as the last time node rewards were paid
        final var rewardsStore = new WritableNetworkStakingRewardsStore(writableStates);
        rewardsStore.put(rewardsStore
                .get()
                .copyBuilder()
                .lastNodeRewardPaymentsTime(asTimestamp(now))
                .build());
        nodeRewardStore.resetForNewStakingPeriod();
        resetNodeRewards();
        ((CommittableWritableStates) writableStates).commit();
    }

    /**
     * Gets the node reward info state from the given state.
     *
     * @param state the state
     * @return the node reward info state
     */
    private @NonNull NodeRewards nodeRewardInfoFrom(@NonNull final State state) {
        final var nodeRewardInfoState =
                state.getReadableStates(TokenService.NAME).<NodeRewards>getSingleton(NODE_REWARDS_KEY);
        return requireNonNull(nodeRewardInfoState.get());
    }

    /**
     * Updates the node reward state in the given state. This method will be called at the end of every block.
     * <p>
     * This method updates the number of rounds in the staking period and the number of missed judge rounds for
     * each node.
     *
     * @param state             the state to update
     * @param nodeFeesCollected the fees collected into reward-eligible node accounts
     */
    private void updateNodeRewardState(@NonNull final State state, final long nodeFeesCollected) {
        final var writableTokenState = state.getWritableStates(TokenService.NAME);
        final var nodeRewardsState = writableTokenState.<NodeRewards>getSingleton(NODE_REWARDS_KEY);
        final var nodeActivities = missedJudgeCounts.entrySet().stream()
                .map(entry -> NodeActivity.newBuilder()
                        .nodeId(entry.getKey())
                        .numMissedJudgeRounds(entry.getValue())
                        .build())
                .toList();
        final long newNodeFeesCollected =
                requireNonNull(nodeRewardsState.get()).nodeFeesCollected() + nodeFeesCollected;
        nodeRewardsState.put(NodeRewards.newBuilder()
                .nodeActivities(nodeActivities)
                .numRoundsInStakingPeriod(roundsThisStakingPeriod)
                .nodeFeesCollected(newNodeFeesCollected)
                .build());
        ((CommittableWritableStates) writableTokenState).commit();
    }

    /**
     * Returns the IDs of the nodes that did not create a judge in the current round.
     *
     * @param state the state
     * @return the IDs of the nodes that did not create a judge in the current round
     */
    private List<Long> missingJudgesInLastRoundOf(@NonNull final State state) {
        final var readablePlatformState =
                state.getReadableStates(PlatformStateService.NAME).<PlatformState>getSingleton(PLATFORM_STATE_KEY);
        final var rosterStore = new ReadableRosterStoreImpl(state.getReadableStates(RosterService.NAME));
        final var judges = requireNonNull(readablePlatformState.get()).consensusSnapshot().judgeIds().stream()
                .map(JudgeId::creatorId)
                .collect(toCollection(HashSet::new));
        return requireNonNull(rosterStore.getActiveRoster()).rosterEntries().stream()
                .map(RosterEntry::nodeId)
                .filter(nodeId -> !judges.contains(nodeId))
                .toList();
    }

    @VisibleForTesting
    public long getRoundsThisStakingPeriod() {
        return roundsThisStakingPeriod;
    }

    @VisibleForTesting
    public SortedMap<Long, Long> getMissedJudgeCounts() {
        return missedJudgeCounts;
    }
}
