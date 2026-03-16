// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token;

import static com.hedera.hapi.util.HapiUtils.ACCOUNT_ID_COMPARATOR;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TransferList;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Encapsulates per-node reward amounts for distribution.
 * This class serves as a self-contained data structure that carries per-node reward amounts,
 * the payer account, and can produce the final TransferList for dispatch.
 */
public class NodeRewardAmounts {

    /**
     * Represents a single reward amount for a specific node.
     */
    public record NodeRewardAmount(long nodeId, AccountID accountId, long amount, RewardType type, boolean active) {}

    /**
     * Types of rewards that can be distributed to nodes.
     */
    public enum RewardType {
        CONSENSUS_NODE,
        BLOCK_NODE
    }

    /** Map of all node rewards, keyed by nodeId. */
    private final Map<Long, List<NodeRewardAmount>> nodeRewards = new TreeMap<>();

    /** The account that will pay for the rewards. */
    private final AccountID payerId;

    /**
     * Creates a new NodeRewardAmounts instance.
     *
     * @param payerId the account that will pay for the rewards
     * @throws NullPointerException if payerId is null
     */
    public NodeRewardAmounts(@NonNull final AccountID payerId) {
        this.payerId = requireNonNull(payerId, "payerId must not be null");
    }

    /**
     * Adds a consensus reward for an active node.
     *
     * @param nodeId the ID of the node
     * @param accountId the account that will receive the reward
     * @param amount the reward amount
     */
    public void addConsensusNodeReward(final long nodeId, @NonNull final AccountID accountId, final long amount) {
        addReward(nodeId, accountId, amount, RewardType.CONSENSUS_NODE, true);
    }

    /**
     * Adds a block node reward for an active node.
     *
     * @param nodeId the ID of the node
     * @param accountId the account that will receive the reward
     * @param amount the reward amount
     */
    public void addBlockNodeReward(final long nodeId, @NonNull final AccountID accountId, final long amount) {
        addReward(nodeId, accountId, amount, RewardType.BLOCK_NODE, true);
    }

    /**
     * Adds a consensus reward for an inactive node.
     *
     * @param nodeId the ID of the node
     * @param accountId the account that will receive the reward
     * @param amount the reward amount
     */
    public void addInactiveConsensusNodeReward(
            final long nodeId, @NonNull final AccountID accountId, final long amount) {
        addReward(nodeId, accountId, amount, RewardType.CONSENSUS_NODE, false);
    }

    /**
     * Adds a reward for a specific node.
     *
     * @param nodeId the ID of the node
     * @param accountId the account that will receive the reward
     * @param amount the reward amount
     * @param type the type of reward
     * @param active whether this is an active or inactive node reward
     */
    private void addReward(
            final long nodeId,
            final AccountID accountId,
            final long amount,
            final RewardType type,
            final boolean active) {
        if (amount < 0) {
            throw new IllegalArgumentException("reward amount must not be negative");
        }
        if (amount == 0) {
            return;
        }

        nodeRewards
                .computeIfAbsent(nodeId, k -> new ArrayList<>())
                .add(new NodeRewardAmount(nodeId, accountId, amount, type, active));
    }

    /**
     * Calculates the total amount of rewards for active nodes.
     *
     * @return the sum of all active reward amounts
     */
    public long activeTotalAmount() {
        return sumRewards(true);
    }

    /**
     * Calculates the total amount of rewards for inactive nodes.
     *
     * @return the sum of all inactive reward amounts
     */
    public long inactiveTotalAmount() {
        return sumRewards(false);
    }

    /**
     * Calculates the total amount of all rewards (active + inactive).
     *
     * @return the sum of all reward amounts
     */
    public long totalAmount() {
        return nodeRewards.values().stream()
                .flatMap(List::stream)
                .mapToLong(NodeRewardAmount::amount)
                .sum();
    }

    /**
     * Checks if there are any rewards to distribute.
     *
     * @return true if no rewards have been added, false otherwise
     */
    public boolean isEmpty() {
        return nodeRewards.isEmpty();
    }

    /**
     * Converts the reward amounts into a TransferList for dispatch.
     * This method aggregates amounts per AccountID (a node may have both consensus and block rewards),
     * and adds the payer debit as the negative of the total amount.
     *
     * @return a TransferList ready for dispatch
     */
    public TransferList toTransferList() {
        if (isEmpty()) {
            return TransferList.newBuilder().build();
        }

        // Aggregate amounts per AccountID
        // Use a tree map to ensure order and determinism, so we guarantee all transactions
        // will be processed at the same order in all nodes.
        final Map<AccountID, Long> aggregatedAmounts = new TreeMap<>(ACCOUNT_ID_COMPARATOR);

        // Process all rewards
        for (final var rewards : nodeRewards.values()) {
            for (final var reward : rewards) {
                aggregatedAmounts.merge(reward.accountId, reward.amount, Long::sum);
            }
        }

        // Add payer debit (negative of total amount)
        final long total = totalAmount();
        if (total > 0) {
            aggregatedAmounts.merge(payerId, -total, Long::sum);
        }

        // Build the account amounts list
        final var accountAmounts = new ArrayList<AccountAmount>(aggregatedAmounts.size());

        // Add all entries with non-zero amounts
        for (final var entry : aggregatedAmounts.entrySet()) {
            if (entry.getValue() != 0) {
                accountAmounts.add(AccountAmount.newBuilder()
                        .accountID(entry.getKey())
                        .amount(entry.getValue())
                        .build());
            }
        }

        return TransferList.newBuilder().accountAmounts(accountAmounts).build();
    }

    /**
     * Creates a copy of this NodeRewardAmounts containing only rewards of active nodes.
     *
     * @return a new NodeRewardAmounts instance with only active rewards
     */
    public NodeRewardAmounts onlyActiveNodeRewards() {
        return withCappedInactiveRewards(0);
    }

    /**
     * Creates a copy of this NodeRewardAmounts keeping all active rewards and capping
     * the total inactive rewards at the given budget. The budget is divided equally
     * among inactive nodes; if the budget is zero, inactive rewards are dropped entirely.
     *
     * @param inactiveBudget the maximum total amount available for inactive rewards
     * @return a new NodeRewardAmounts with active rewards unchanged and inactive rewards capped
     */
    public NodeRewardAmounts withCappedInactiveRewards(final long inactiveBudget) {
        final var result = new NodeRewardAmounts(payerId);
        final int inactiveCount = inactiveNodeCount();
        final long perInactiveNode = inactiveBudget > 0 && inactiveCount > 0 ? inactiveBudget / inactiveCount : 0;
        for (final var entry : nodeRewards.entrySet()) {
            for (final var reward : entry.getValue()) {
                if (reward.active()) {
                    result.addReward(reward.nodeId(), reward.accountId(), reward.amount(), reward.type(), true);
                } else if (perInactiveNode > 0) {
                    result.addReward(reward.nodeId(), reward.accountId(), perInactiveNode, reward.type(), false);
                }
            }
        }
        return result;
    }

    /**
     * Gets a list of all active node rewards.
     *
     * @return a list containing only the RewardAmount of active nodes
     */
    public List<NodeRewardAmount> activeNodeRewards() {
        return nodeRewards.values().stream()
                .flatMap(List::stream)
                .filter(NodeRewardAmount::active)
                .toList();
    }

    /**
     * Counts the number of unique active nodes with rewards.
     *
     * @return the count of active nodes
     */
    public int activeNodeCount() {
        return (int) nodeRewards.entrySet().stream()
                .filter(entry -> entry.getValue().stream().anyMatch(NodeRewardAmount::active))
                .count();
    }

    /**
     * Counts the number of unique inactive nodes with rewards (nodes whose rewards are all inactive).
     *
     * @return the count of inactive nodes
     */
    public int inactiveNodeCount() {
        return (int) nodeRewards.entrySet().stream()
                .filter(entry -> entry.getValue().stream().noneMatch(NodeRewardAmount::active))
                .count();
    }

    /**
     * Returns a human-readable breakdown of rewards grouped by identical reward structure.
     * Nodes that share the same per-type amounts are collapsed into a single line, keeping
     * the output concise regardless of network size. Each group shows the node IDs and the
     * amount contributed by each reward type.
     *
     * <p>Example output:
     * <pre>
     * NodeRewardAmounts{
     *   activeNodes=[
     *     Nodes[1, 2]: CONSENSUS_NODE=120 BLOCK_NODE=10
     *     Nodes[3]: CONSENSUS_NODE=10
     *   ]
     *   inactiveNodes=[
     *     Nodes[7]: CONSENSUS_NODE=5
     *   ]
     *   totals: active=260, inactive=5
     * }
     * </pre>
     */
    @Override
    public String toString() {
        if (nodeRewards.isEmpty()) {
            return "NodeRewardAmounts{empty}";
        }

        // Group nodes by their per-type reward breakdown; LinkedHashMap preserves nodeId insertion order
        final Map<Map<RewardType, Long>, List<Long>> activeGroups = new LinkedHashMap<>();
        final Map<Map<RewardType, Long>, List<Long>> inactiveGroups = new LinkedHashMap<>();

        for (final var entry : nodeRewards.entrySet()) {
            final long nodeId = entry.getKey();
            final boolean isActive = entry.getValue().stream().anyMatch(NodeRewardAmount::active);

            // Sum amounts per RewardType for this node; HashMap.equals() is content-based
            final var breakdown = new HashMap<RewardType, Long>();
            for (final var reward : entry.getValue()) {
                breakdown.merge(reward.type(), reward.amount(), Long::sum);
            }

            if (isActive) {
                activeGroups.computeIfAbsent(breakdown, k -> new ArrayList<>()).add(nodeId);
            } else {
                inactiveGroups
                        .computeIfAbsent(breakdown, k -> new ArrayList<>())
                        .add(nodeId);
            }
        }

        final var builder = new StringBuilder("NodeRewardAmounts{");
        appendGroups(builder, "activeNodes", activeGroups);
        appendGroups(builder, "inactiveNodes", inactiveGroups);
        builder.append("\n  totals: active=")
                .append(activeTotalAmount())
                .append(", inactive=")
                .append(inactiveTotalAmount())
                .append("\n}");
        return builder.toString();
    }

    private static void appendGroups(
            @NonNull final StringBuilder builder,
            @NonNull final String label,
            @NonNull final Map<Map<RewardType, Long>, List<Long>> groups) {
        if (groups.isEmpty()) {
            return;
        }
        builder.append("\n  ").append(label).append("=[");
        for (final var group : groups.entrySet()) {
            builder.append("\n    Nodes").append(group.getValue()).append(':');
            // TreeMap gives deterministic enum-declaration order (CONSENSUS_NODE before BLOCK_NODE)
            new TreeMap<>(group.getKey())
                    .forEach((type, amount) ->
                            builder.append(' ').append(type).append('=').append(amount));
        }
        builder.append("\n  ]");
    }

    /**
     * Helper method to sum rewards based on active status.
     */
    private long sumRewards(final boolean active) {
        return nodeRewards.values().stream()
                .flatMap(List::stream)
                .filter(r -> r.active() == active)
                .mapToLong(NodeRewardAmount::amount)
                .sum();
    }
}
