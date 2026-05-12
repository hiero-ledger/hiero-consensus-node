// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.metrics;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.token.NodeRewardAmounts;
import com.hedera.node.app.service.token.NodeRewardAmounts.NodeRewardAmount;
import com.swirlds.metrics.api.DoubleGauge;
import com.swirlds.metrics.api.LongGauge;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.commons.lang3.stream.Streams;
import org.hiero.consensus.metrics.RunningAverageMetric;

@Singleton
public class NodeMetrics {
    private static final String APP_CATEGORY = "app_";
    private static final String[] NODE_REWARD_METRIC_LABELS = buildRewardMetricLabels();

    // Maximum number of distinct nodes whose reward gauges are tracked. Each node can contribute
    // up to 2 gauge entries (CONSENSUS_NODE + BLOCK_NODE for active nodes) or 1 (INACTIVE).
    // 100 nodes safely covers current and projected network sizes.
    static final int MAX_TRACKED_NODES = 100;

    private final Map<Long, RunningAverageMetric> activeRoundsAverages = new ConcurrentHashMap<>();
    private final Map<Long, DoubleGauge> activeRoundsSnapshots = new ConcurrentHashMap<>();
    /**
     * Per-node, per-reward-type gauges. The key is a combination of node ID and reward type.
     */
    private final Map<String, LongGauge> nodeRewardTinybarsGauges = new ConcurrentHashMap<>();
    /**
     * The set of node IDs currently tracked by reward gauges.
     */
    private final Set<Long> trackedNodeIds = new HashSet<>();

    private final Metrics metrics;
    private final LongGauge blockNodeRewardsEligibleCount;

    @Inject
    public NodeMetrics(@NonNull final Metrics metrics) {
        this.metrics = requireNonNull(metrics);
        this.blockNodeRewardsEligibleCount =
                metrics.getOrCreate(new LongGauge.Config(APP_CATEGORY, "blockNodeRewardsEligibleCount")
                        .withDescription("Number of consensus nodes eligible for block node rewards this period"));
    }

    /**
     * Registers the metrics for the active round % for each node in the given roster.
     *
     * @param nodeIds the list of node ids
     */
    public void registerNodeMetrics(@NonNull Collection<Long> nodeIds) {
        for (final var nodeId : nodeIds) {
            final String name = "nodeActivePercent_node" + nodeId;
            final String snapshotName = "nodeActivePercentSnapshot_node" + nodeId;

            if (!activeRoundsAverages.containsKey(nodeId)) {
                final var averageMetric = metrics.getOrCreate(new RunningAverageMetric.Config(APP_CATEGORY, name)
                        .withDescription("Active round % average for node " + nodeId));
                activeRoundsAverages.put(nodeId, averageMetric);
            }

            if (!activeRoundsSnapshots.containsKey(nodeId)) {
                final var snapshot = metrics.getOrCreate(new DoubleGauge.Config(APP_CATEGORY, snapshotName)
                        .withDescription("Active round % snapshot for node " + nodeId));
                activeRoundsSnapshots.put(nodeId, snapshot);
            }
        }
    }

    /**
     * Updates the active round percentage for a node.
     *
     * @param nodeId        the node ID
     * @param activePercent the active round percentage
     */
    public void updateNodeActiveMetrics(final long nodeId, final double activePercent) {
        if (activeRoundsAverages.containsKey(nodeId)) {
            activeRoundsAverages.get(nodeId).update(activePercent);
        }
        if (activeRoundsSnapshots.containsKey(nodeId)) {
            activeRoundsSnapshots.get(nodeId).set(activePercent);
        }
    }

    /**
     * Updates all reward-related metrics after reward calculation.
     *
     * @param rewardAmounts          the calculated (possibly budget-constrained) reward amounts
     * @param blockNodeEligibleCount the number of consensus nodes eligible for block node rewards
     */
    public void updateRewardMetrics(@NonNull final NodeRewardAmounts rewardAmounts, final int blockNodeEligibleCount) {
        blockNodeRewardsEligibleCount.set(blockNodeEligibleCount);
        updatePerNodeRewardMetrics(rewardAmounts);
    }

    /**
     * Updates per-node, per-reward-type tinybars gauges from the given reward amounts. Each gauge is named
     * {@code nodeRewardTinybars_node{N}_{label}} where label is one of {@code CONSENSUS_NODE},
     * {@code BLOCK_NODE} (for active nodes), or {@code INACTIVE} (for inactive nodes, regardless of type).
     *
     * <p>To avoid unbounded metric cardinality, only the first {@value #MAX_TRACKED_NODES} distinct
     * nodes (sorted by ascending node ID) are sampled. Nodes outside the sample have their gauges
     * removed from the metrics system.
     */
    private void updatePerNodeRewardMetrics(@NonNull final NodeRewardAmounts rewardAmounts) {
        // Determine which node IDs to sample (bounded by MAX_TRACKED_NODES)
        final var sampledNodeIds =
                rewardAmounts.nodeIds().stream().limit(MAX_TRACKED_NODES).collect(Collectors.toSet());

        // Evict all trackedNodeIds that are not part of the new set of sampledNodeIds
        // It copies the original tracked ids and removes the ones still present in the sample.
        final var nodeIdsToRemove = new HashSet<>(trackedNodeIds);
        nodeIdsToRemove.removeAll(sampledNodeIds);

        nodeIdsToRemove.forEach(this::evictGaugesForNode);
        trackedNodeIds.removeAll(nodeIdsToRemove);

        // Reset all currently tracked gauges before applying new values
        nodeRewardTinybarsGauges.values().forEach(g -> g.set(0L));

        // Apply rewards for sampled nodes
        for (final var reward : rewardAmounts.rewardsForNodes(sampledNodeIds)) {
            final String metricName = rewardMetricName(reward);
            final LongGauge gauge = nodeRewardTinybarsGauges.computeIfAbsent(
                    metricName,
                    ignored -> metrics.getOrCreate(new LongGauge.Config(APP_CATEGORY, metricName)
                            .withDescription(
                                    "Reward tinybars for node " + reward.nodeId() + " (" + metricLabel(reward) + ")")));
            gauge.set(reward.amount());
            trackedNodeIds.add(reward.nodeId());
        }
    }

    /**
     * Removes all reward gauges for the given node from both the local cache and the metrics system.
     */
    private void evictGaugesForNode(final long nodeId) {
        final String nodeIdMetricPrefix = prefixWithNodeId(nodeId);
        Streams.of(NODE_REWARD_METRIC_LABELS).forEach(type -> {
            final var metricName = nodeIdMetricPrefix + type;
            metrics.remove(new LongGauge.Config(APP_CATEGORY, metricName));
            nodeRewardTinybarsGauges.remove(metricName);
        });
    }

    /**
     * Constructs a reward metric name for the given node reward by concatenating the node ID prefix with the metric
     * label. The final pattern is {@code nodeRewardTinybars_node{N}_{label}}
     */
    private static String rewardMetricName(final NodeRewardAmount reward) {
        return prefixWithNodeId(reward.nodeId()) + metricLabel(reward);
    }

    private static String prefixWithNodeId(final long nodeId) {
        return "nodeRewardTinybars_node" + nodeId + "_";
    }

    /**
     * Returns the metric label for the given reward. Active nodes use their reward type name; inactive nodes use
     * {@code "INACTIVE"} regardless of type.
     *
     * <p><b>WARNING:</b> these label strings are part of the published metric name. Renaming
     * {@link NodeRewardAmounts.RewardType} enum constants is a breaking change for dashboards and alerting rules.
     */
    private static String metricLabel(final NodeRewardAmount reward) {
        return reward.active() ? reward.type().name() : "INACTIVE";
    }

    /**
     * Builds the complete set of metric label suffixes from the {@link NodeRewardAmounts.RewardType} enum
     * plus the synthetic {@code "INACTIVE"} label. Derived at class-load time so that
     * {@link #evictGaugesForNode(long)} stays in sync if new reward types are added.
     */
    private static String[] buildRewardMetricLabels() {
        final var types = NodeRewardAmounts.RewardType.values();
        final var labels = new String[types.length + 1];
        labels[0] = "INACTIVE";
        for (int i = 0; i < types.length; i++) {
            labels[i + 1] = types[i].name();
        }
        return labels;
    }
}
