// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.services;

import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.hapi.fees.pricing.FeeSchedules.USD_TO_TINYCENTS;
import static com.hedera.node.app.service.token.impl.schemas.V0610TokenSchema.NODE_REWARDS_STATE_ID;
import static com.hedera.node.app.workflows.handle.steps.StakePeriodChanges.isNextStakingPeriod;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toMap;
import static org.hiero.consensus.platformstate.V0540PlatformStateSchema.PLATFORM_STATE_STATE_ID;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.node.state.token.NodeActivity;
import com.hedera.hapi.node.state.token.NodeRewards;
import com.hedera.hapi.platform.state.JudgeId;
import com.hedera.hapi.platform.state.PlatformState;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.metrics.NodeMetrics;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.node.app.service.entityid.impl.ReadableEntityIdStoreImpl;
import com.hedera.node.app.service.roster.RosterService;
import com.hedera.node.app.service.token.NodeRewardActivity;
import com.hedera.node.app.service.token.NodeRewardAmounts;
import com.hedera.node.app.service.token.NodeRewardGroups;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.ReadableAccountStoreImpl;
import com.hedera.node.app.service.token.impl.ReadableNetworkStakingRewardsStoreImpl;
import com.hedera.node.app.service.token.impl.ReadableNodeRewardsStoreImpl;
import com.hedera.node.app.service.token.impl.WritableNetworkStakingRewardsStore;
import com.hedera.node.app.service.token.impl.WritableNodeRewardsStoreImpl;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.workflows.handle.record.SystemTransactions;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.NodesConfig;
import com.hedera.node.config.data.StakingConfig;
import com.swirlds.state.State;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.platformstate.PlatformStateService;
import org.hiero.consensus.roster.ReadableRosterStoreImpl;

/**
 * Manages the node rewards for the network. This includes tracking the number of rounds in the current staking
 * period, the number of missed judge rounds for each node, and the fees collected by all nodes in a staking period.
 * This class is responsible for updating the node rewards state at the end of each block and for paying
 * rewards to active nodes at the end of each staking period.
 */
@Singleton
public class NodeRewardManager {

    private static final Logger log = LogManager.getLogger(NodeRewardManager.class);
    private final ConfigProvider configProvider;
    private final EntityIdFactory entityIdFactory;
    private final ExchangeRateManager exchangeRateManager;
    private final NetworkInfo networkInfo;

    // The number of rounds so far in the staking period
    private long roundsThisStakingPeriod = 0;
    // The number of rounds each node missed creating judge. This is updated from state at the start of every round
    // and will be written back to state at the end of every block
    private final SortedMap<Long, Long> missedJudgeCounts = new TreeMap<>();
    private final NodeMetrics metrics;

    @Inject
    public NodeRewardManager(
            @NonNull final ConfigProvider configProvider,
            @NonNull final EntityIdFactory entityIdFactory,
            @NonNull final ExchangeRateManager exchangeRateManager,
            @NonNull final NetworkInfo networkInfo,
            @NonNull final NodeMetrics metrics) {
        this.configProvider = requireNonNull(configProvider);
        this.entityIdFactory = requireNonNull(entityIdFactory);
        this.exchangeRateManager = requireNonNull(exchangeRateManager);
        this.metrics = metrics;
        this.networkInfo = requireNonNull(networkInfo);
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
     *
     * @param state the state
     * @param nodeFeesCollected the fees collected into node accounts in the block
     */
    public void onCloseBlock(@NonNull final State state, final long nodeFeesCollected) {
        final NodesConfig nodesConfig = configProvider.getConfiguration().getConfigData(NodesConfig.class);
        // If node rewards are enabled, we need to update the node rewards state with the current round and missed
        // judge counts.
        if (nodesConfig.nodeRewardsEnabled()) {
            updateNodeRewardState(state, nodeFeesCollected);
            final var nodeRewardStore = new ReadableNodeRewardsStoreImpl(state.getWritableStates(TokenService.NAME));
            final var nodeActivities = buildNodeActivities(
                    getRosterEntries(state), nodeRewardStore.get(), nodesConfig.activeRoundsPercent());
            updateNodeMetrics(nodeActivities);
        }
    }

    /**
     * Updates the number of rounds in the current staking period and the number of missed judge rounds for each node.
     * This method is called at the end of each round.
     *
     * @param state the state
     */
    public void updateJudgesOnEndRound(State state) {
        roundsThisStakingPeriod++;
        // Track missing judges in this round
        missingJudgesInLastRoundOf(state)
                .forEach(nodeId -> missedJudgeCounts.compute(nodeId, (k, v) -> (v == null) ? 1 : v + 1));
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
     * @return whether the node rewards were paid
     */
    public boolean maybeRewardActiveNodes(
            @NonNull final State state, @NonNull final Instant now, final SystemTransactions systemTransactions) {
        final var config = configProvider.getConfiguration();
        final var nodesConfig = config.getConfigData(NodesConfig.class);
        if (!nodesConfig.nodeRewardsEnabled()) {
            return false;
        }
        final var lastNodeRewardsPaymentTime = classifyLastNodeRewardsPaymentTime(state, now);
        // If we're in the same staking period as the last time node rewards were paid, we don't
        // need to do anything
        if (lastNodeRewardsPaymentTime == LastNodeRewardsPaymentTime.CURRENT_PERIOD) {
            return false;
        }

        final var writableStates = state.getWritableStates(TokenService.NAME);
        final var nodeRewardStore = new WritableNodeRewardsStoreImpl(writableStates);
        final var currentRoster = getRosterEntries(state);

        // Don't try to pay rewards in the genesis edge case when LastNodeRewardsPaymentTime.NEVER
        if (lastNodeRewardsPaymentTime == LastNodeRewardsPaymentTime.PREVIOUS_PERIOD) {
            log.info("Considering paying node rewards for the last staking period at {}", asTimestamp(now));
            // Build activities for all known roster nodes (includes declining nodes)
            final var nodeActivities =
                    buildNodeActivities(currentRoster, nodeRewardStore.get(), nodesConfig.activeRoundsPercent());
            // Update metrics for all nodes (active, inactive, and declining)
            updateNodeMetrics(nodeActivities);
            // Exclude declining nodes and partition the remainder into active/inactive groups for reward dispatch
            final var nodeGroups = NodeRewardGroups.from(excludeNodesDecliningRewards(nodeActivities));

            // And pay whatever rewards the network can afford
            final long rewardAccountBalance = getRewardAccountBalance(state, writableStates);
            final long prePaidRewards = nodesConfig.adjustNodeFees()
                    ? nodeRewardStore.get().nodeFeesCollected() / currentRoster.size()
                    : 0L;

            // Calculate the reward amounts with budget constraints applied
            final var rewardAmounts =
                    calculateRewardAmounts(nodeGroups, rewardAccountBalance, nodesConfig, now, prePaidRewards);

            // Dispatch the calculated rewards
            systemTransactions.dispatchNodeRewards(state, now, rewardAmounts);
        }
        // Record this as the last time node rewards were paid
        updateRewardLastPaymentTime(now, writableStates);
        resetStakingPeriodRewards(nodeRewardStore);
        ((CommittableWritableStates) writableStates).commit();
        return true;
    }

    private void updateRewardLastPaymentTime(@NonNull final Instant now, final WritableStates writableStates) {
        final var rewardsStore = new WritableNetworkStakingRewardsStore(writableStates);
        rewardsStore.put(rewardsStore
                .get()
                .copyBuilder()
                .lastNodeRewardPaymentsTime(asTimestamp(now))
                .build());
    }

    private void resetStakingPeriodRewards(final WritableNodeRewardsStoreImpl nodeRewardStore) {
        nodeRewardStore.resetForNewStakingPeriod();
        resetNodeRewards();
    }

    private static @NonNull List<RosterEntry> getRosterEntries(@NonNull State state) {
        final var rosterStore = new ReadableRosterStoreImpl(state.getReadableStates(RosterService.NAME));
        return requireNonNull(rosterStore.getActiveRoster()).rosterEntries();
    }

    private void updateNodeMetrics(@NonNull final Collection<NodeRewardActivity> activities) {
        final var nodeIds = activities.stream().map(NodeRewardActivity::nodeId).collect(toCollection(HashSet::new));
        metrics.registerNodeMetrics(nodeIds);
        activities.forEach(activity -> metrics.updateNodeActiveMetrics(activity.nodeId(), activity.activePercent()));
    }

    /**
     * Gets the node reward info state from the given state.
     *
     * @param state the state
     * @return the node reward info state
     */
    private @NonNull NodeRewards nodeRewardInfoFrom(@NonNull final State state) {
        final var nodeRewardInfoState =
                state.getReadableStates(TokenService.NAME).<NodeRewards>getSingleton(NODE_REWARDS_STATE_ID);
        return Optional.ofNullable(nodeRewardInfoState.get()).orElse(NodeRewards.DEFAULT);
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
        final var nodeRewardsState = writableTokenState.<NodeRewards>getSingleton(NODE_REWARDS_STATE_ID);
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
                state.getReadableStates(PlatformStateService.NAME).<PlatformState>getSingleton(PLATFORM_STATE_STATE_ID);
        final var rosterStore = new ReadableRosterStoreImpl(state.getReadableStates(RosterService.NAME));
        final var judges = requireNonNull(readablePlatformState.get()).consensusSnapshot().judgeIds().stream()
                .map(JudgeId::creatorId)
                .collect(toCollection(HashSet::new));
        return requireNonNull(rosterStore.getActiveRoster()).rosterEntries().stream()
                .map(RosterEntry::nodeId)
                .filter(nodeId -> !judges.contains(nodeId))
                .toList();
    }

    /**
     * Builds the list of node reward activities from the given roster entries and node rewards
     * state. Nodes not found in the network info (unknown nodes) are excluded. Both reward-eligible
     * and declining-reward nodes are included; use {@link #excludeNodesDecliningRewards} to filter
     * out declining nodes before reward dispatch.
     *
     * @param rosterEntries           the roster entries to evaluate
     * @param nodeRewards             the node rewards state containing missed judge counts
     * @param minJudgeRoundPercentage the minimum percentage of judge rounds for a node to be active
     * @return the list of node reward activities for all known roster nodes
     */
    @VisibleForTesting
    List<NodeRewardActivity> buildNodeActivities(
            @NonNull final List<RosterEntry> rosterEntries,
            @NonNull final NodeRewards nodeRewards,
            final int minJudgeRoundPercentage) {
        final long roundsLastPeriod = nodeRewards.numRoundsInStakingPeriod();
        final var missedJudgesByNode = nodeRewards.nodeActivities().stream()
                .collect(toMap(NodeActivity::nodeId, NodeActivity::numMissedJudgeRounds));

        return rosterEntries.stream()
                .map(entry -> {
                    final var nodeInfo = networkInfo.nodeInfo(entry.nodeId());
                    if (nodeInfo == null) {
                        log.error("Node {} not found in network info", entry.nodeId());
                        return null;
                    }
                    final long missedJudges = missedJudgesByNode.getOrDefault(entry.nodeId(), 0L);
                    return new NodeRewardActivity(
                            entry.nodeId(),
                            nodeInfo.accountId(),
                            missedJudges,
                            roundsLastPeriod,
                            minJudgeRoundPercentage);
                })
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Filters out activities for nodes that are declining rewards, returning only reward-eligible
     * activities. Nodes not found in the network info are also excluded.
     *
     * @param activities the full list of node activities (may include declining nodes)
     * @return the filtered list containing only reward-eligible node activities
     */
    @VisibleForTesting
    List<NodeRewardActivity> excludeNodesDecliningRewards(@NonNull final List<NodeRewardActivity> activities) {
        return activities.stream()
                .filter(activity -> {
                    final var nodeInfo = networkInfo.nodeInfo(activity.nodeId());
                    return nodeInfo != null && !nodeInfo.declineReward();
                })
                .toList();
    }

    /**
     * Calculates reward amounts for all nodes based on activity and available balance.
     * This method centralizes all reward calculation and budget constraint logic,
     * including computing per-node amounts from configuration and applying budget constraints.
     *
     * @param nodeGroups the groups of active and inactive nodes
     * @param rewardAccountBalance the available balance in the rewards account
     * @param nodesConfig the nodes configuration
     * @param now the current consensus time (used for exchange rate conversion)
     * @param prePaidRewards the per-node amount already pre-paid via node fees
     * @return the calculated reward amounts ready for dispatch
     */
    @VisibleForTesting
    NodeRewardAmounts calculateRewardAmounts(
            @NonNull final NodeRewardGroups nodeGroups,
            final long rewardAccountBalance,
            @NonNull final NodesConfig nodesConfig,
            @NonNull final Instant now,
            final long prePaidRewards) {
        final var payerId = rewardsAccountId();
        final long minNodeReward = computeMinNodeReward(nodesConfig, now);
        final var rewardAmounts = new NodeRewardAmounts(payerId);
        final var activeAccounts = nodeGroups.activeNodeAccountIds();
        final var inactiveAccounts = nodeGroups.inactiveNodeAccountIds();

        if (!activeAccounts.isEmpty()) {
            log.info("Found eligible active node accounts {}", activeAccounts);
        }

        // Step 1: Add consensus rewards for active nodes (per-node amount computed inside)
        computeActiveConsensusNodeRewards(
                nodeGroups.activeNodeActivities(), nodesConfig, now, prePaidRewards, minNodeReward, rewardAmounts);

        // Step 2: Add block node rewards for active nodes (stub for HIP-1357)
        computeActiveBlockNodeRewards(nodeGroups.activeNodeActivities(), rewardAmounts);

        // Step 3: Add consensus rewards for inactive nodes (only if minimum reward > 0)
        if (minNodeReward > 0 && !inactiveAccounts.isEmpty()) {
            log.info(
                    "Found inactive node accounts {} that will receive minimum node reward {}",
                    inactiveAccounts,
                    minNodeReward);
            computeInactiveConsensusNodeRewards(nodeGroups.inactiveNodeActivities(), minNodeReward, rewardAmounts);
        }

        // Step 4: Apply budget constraints
        final var constrained = applyBudgetConstraints(rewardAmounts, rewardAccountBalance, payerId);
        log.info("Calculated rewards: {}", constrained);
        return constrained;
    }

    /**
     * Computes the per-consensus-node reward and adds it to the reward amounts for all active nodes.
     * The per-node amount is the target yearly reward adjusted for pre-paid fees, floored at {@code minNodeReward}.
     *
     * @param activities the active node activities to reward
     * @param nodesConfig the nodes configuration
     * @param now the current consensus time (used for exchange rate conversion)
     * @param prePaidRewards the per-node amount already pre-paid via node fees
     * @param minNodeReward the minimum reward floor (shared with inactive reward computation)
     * @param rewardAmounts the mutable reward amounts to update
     */
    @VisibleForTesting
    void computeActiveConsensusNodeRewards(
            @NonNull final Collection<NodeRewardActivity> activities,
            @NonNull final NodesConfig nodesConfig,
            @NonNull final Instant now,
            final long prePaidRewards,
            final long minNodeReward,
            @NonNull final NodeRewardAmounts rewardAmounts) {
        final var targetPayInTinycents = BigInteger.valueOf(nodesConfig.targetYearlyNodeRewardsUsd())
                .multiply(USD_TO_TINYCENTS.toBigInteger())
                .divide(BigInteger.valueOf(nodesConfig.numPeriodsToTargetUsd()));
        final long targetNodeReward =
                exchangeRateManager.getTinybarsFromTinycents(targetPayInTinycents.longValue(), now);
        final long perNodeReward = Math.max(minNodeReward, targetNodeReward - prePaidRewards);
        for (final var activity : activities) {
            rewardAmounts.addConsensusNodeReward(activity.nodeId(), activity.accountId(), perNodeReward);
        }
    }

    /**
     * Computes and adds block node rewards for active nodes.
     * This is a stub for future HIP-1357 implementation.
     *
     * @param activities the active node activities to reward
     * @param rewardAmounts the mutable reward amounts to update
     */
    @VisibleForTesting
    void computeActiveBlockNodeRewards(
            @NonNull final Collection<NodeRewardActivity> activities, @NonNull final NodeRewardAmounts rewardAmounts) {
        // Stub implementation for future HIP-1357
        // Block node rewards will be calculated and added here
        // For now, no block rewards are added
    }

    /**
     * Computes and adds consensus node rewards for inactive nodes.
     *
     * @param activities the inactive node activities to reward
     * @param amount the minimum reward amount per node
     * @param rewardAmounts the mutable reward amounts to an update
     */
    @VisibleForTesting
    void computeInactiveConsensusNodeRewards(
            @NonNull final Collection<NodeRewardActivity> activities,
            final long amount,
            @NonNull final NodeRewardAmounts rewardAmounts) {
        for (final var activity : activities) {
            rewardAmounts.addInactiveConsensusNodeReward(activity.nodeId(), activity.accountId(), amount);
        }
    }

    /**
     * Returns the account ID of the node rewards account, derived from configuration.
     */
    private AccountID rewardsAccountId() {
        return entityIdFactory.newAccountId(configProvider
                .getConfiguration()
                .getConfigData(AccountsConfig.class)
                .nodeRewardAccount());
    }

    /**
     * Applies budget constraints to the desired reward amounts.
     * If the total desired rewards exceed the available balance, this method
     * adjusts the amounts according to the following priority:
     * 1. If balance greater than or equal to activeTotal + inactiveTotal: keep all amounts
     * 2. If balance greater than activeTotal: keep active, partially fund inactive with the remainder
     * 3. If balance is less than or equal to activeTotal: divide balance equally among active nodes, drop inactive
     * 4. When there are no active nodes, inactive nodes are treated as the sole recipients
     * and the budget is divided equally among them, using the minimal rewards.
     *
     * @param desiredAmounts the desired reward amounts
     * @param availableBalance the available balance in the rewards account
     * @param payerId the account that will pay for the rewards
     * @return the adjusted reward amounts that fit within the budget
     */
    @VisibleForTesting
    NodeRewardAmounts applyBudgetConstraints(
            @NonNull final NodeRewardAmounts desiredAmounts,
            final long availableBalance,
            @NonNull final AccountID payerId) {
        final long activeTotal = desiredAmounts.activeTotalAmount();
        final long inactiveTotal = desiredAmounts.inactiveTotalAmount();
        final long totalDesired = activeTotal + inactiveTotal;

        // Case 1: Sufficient balance for all rewards
        if (totalDesired <= availableBalance) {
            return desiredAmounts;
        }

        // Case 2: Sufficient balance for active nodes, partially fund inactive with the remainder
        if (availableBalance > activeTotal && activeTotal > 0) {
            final long inactiveBudget = Math.min(availableBalance - activeTotal, inactiveTotal);
            return desiredAmounts.withCappedInactiveRewards(inactiveBudget);
        }

        // Case 3: Insufficient balance even for active nodes — divide equally among active nodes.
        // Drops inactive nodes rewards.
        if (activeTotal > 0) {
            final var constrainedAmounts = new NodeRewardAmounts(payerId);
            final var activeNodeCount = countActiveNodes(desiredAmounts);
            if (activeNodeCount > 0) {
                final long perNodeAmount = availableBalance / activeNodeCount;
                log.info("Balance insufficient for all, rewarding active nodes only: {} tinybars each", perNodeAmount);
                distributeEquallyAmongActiveNodes(desiredAmounts, constrainedAmounts, perNodeAmount);
            }
            return constrainedAmounts;
        }

        // Case 4: No active nodes — divide balance equally among inactive nodes
        final long inactiveBudget = Math.min(availableBalance, inactiveTotal);
        return desiredAmounts.withCappedInactiveRewards(inactiveBudget);
    }

    /**
     * Returns the tinybar balance of the rewards account.
     */
    private long getRewardAccountBalance(@NonNull final State state, @NonNull final ReadableStates tokenStates) {
        final var rewardsAccountId = rewardsAccountId();
        final var entityCounters = new ReadableEntityIdStoreImpl(state.getReadableStates(EntityIdService.NAME));
        final var accountStore = new ReadableAccountStoreImpl(tokenStates, entityCounters);
        return requireNonNull(accountStore.getAccountById(rewardsAccountId)).tinybarBalance();
    }

    /**
     * Computes the minimum per-node reward in tinybars for the given period, converting the value in USD
     * to tinybars.
     */
    private long computeMinNodeReward(@NonNull final NodesConfig nodesConfig, @NonNull final Instant now) {
        final long usdAsTinycents = BigInteger.valueOf(nodesConfig.minPerPeriodNodeRewardUsd())
                .multiply(USD_TO_TINYCENTS.toBigInteger())
                .longValue();
        final long minTinycents = Math.max(0L, usdAsTinycents);
        return exchangeRateManager.getTinybarsFromTinycents(minTinycents, now);
    }

    /**
     * Counts the number of unique active nodes with rewards.
     */
    private long countActiveNodes(@NonNull final NodeRewardAmounts amounts) {
        return amounts.activeNodeCount();
    }

    /**
     * Distributes the given amount equally among all active nodes.
     */
    private void distributeEquallyAmongActiveNodes(
            @NonNull final NodeRewardAmounts source,
            @NonNull final NodeRewardAmounts destination,
            final long perNodeAmount) {
        // set to prevent duplicate node rewards, as now we are paying each node just once.
        final var seenNodes = new HashSet<Long>();
        for (final var reward : source.activeNodeRewards()) {
            if (seenNodes.add(reward.nodeId())) {
                destination.addConsensusNodeReward(reward.nodeId(), reward.accountId(), perNodeAmount);
            }
        }
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
