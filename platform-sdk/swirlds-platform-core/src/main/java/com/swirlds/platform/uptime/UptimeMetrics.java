// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.uptime;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.metrics.FunctionGauge;
import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Supplier;
import org.hiero.consensus.model.node.NodeId;

/**
 * Metrics that track node uptime.
 */
class UptimeMetrics {

    private static final String CATEGORY = "platform";

    private final Metrics metrics;

    /**
     * A map from node to the number of rounds since the last consensus event was observed from that node.
     */
    private final Map<NodeId, RunningAverageMetric> roundsSinceLastConsensusEvent = new HashMap<>();

    /**
     * A map from node to the number of rounds since the last judge was observed from that node.
     */
    private final Map<NodeId, RunningAverageMetric> roundsSinceLastJudge = new HashMap<>();

    private static final RunningAverageMetric.Config HEALTHY_NETWORK_FRACTION_CONFIG = new RunningAverageMetric.Config(
                    CATEGORY, "healthyNetworkFraction")
            .withUnit("fraction")
            .withDescription(
                    "The fraction (out of 1.0) of the network that is alive and healthy, weighted by consensus weight.");
    private final RunningAverageMetric healthyNetworkFraction;

    private static final RunningAverageMetric.Config UPTIME_COMPUTATION_TIME = new RunningAverageMetric.Config(
                    CATEGORY, "uptimeComputationTime")
            .withUnit("microseconds")
            .withDescription("The time, in microseconds, required to compute uptime information each round.");
    private final RunningAverageMetric uptimeComputationTime;

    private static final String ROUNDS_SINCE_LAST_CONSENSUS_EVENT = "roundsSinceLastConsensusEvent_";

    /**
     * Construct a new uptime metrics object.
     *
     * @param metrics     the metrics for this node
     * @param roster      the current roster
     * @param isDegraded  a supplier that returns true if this node is degraded, false otherwise
     */
    public UptimeMetrics(
            @NonNull final Metrics metrics, @NonNull final Roster roster, @NonNull final Supplier<Boolean> isDegraded) {

        this.metrics = Objects.requireNonNull(metrics);
        Objects.requireNonNull(roster);
        Objects.requireNonNull(isDegraded);

        healthyNetworkFraction = metrics.getOrCreate(HEALTHY_NETWORK_FRACTION_CONFIG);

        final FunctionGauge.Config<Boolean> degradedConfig = new FunctionGauge.Config<>(
                        CATEGORY, "degraded", Boolean.class, isDegraded)
                .withUnit("boolean")
                .withDescription("False if this node is healthy, true if this node is degraded.");
        metrics.getOrCreate(degradedConfig);

        uptimeComputationTime = metrics.getOrCreate(UPTIME_COMPUTATION_TIME);

        roster.rosterEntries().forEach(entry -> addMetricsForNode(NodeId.of(entry.nodeId())));
    }

    /**
     * Add the metrics for a node.
     *
     * @param nodeId the id of the node
     */
    public void addMetricsForNode(@NonNull final NodeId nodeId) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");

        final RunningAverageMetric.Config roundsSinceLastConensusEventConfig = new RunningAverageMetric.Config(
                        CATEGORY, ROUNDS_SINCE_LAST_CONSENSUS_EVENT + nodeId)
                .withUnit("rounds")
                .withDescription(
                        "The number of rounds since the last consensus event created by this node was observed");
        roundsSinceLastConsensusEvent.put(nodeId, metrics.getOrCreate(roundsSinceLastConensusEventConfig));

        // Temporarily disabled until we properly detect judges in a round
        //        final RunningAverageMetric.Config roundsSinceLastJudgeConfig = new RunningAverageMetric.Config(
        //                        CATEGORY, ROUNDS_SINCE_LAST_JUDGE + nodeId)
        //                .withUnit("rounds")
        //                .withDescription("The number of rounds since the last judge created by this node was
        // observed");
        //        roundsSinceLastJudge.put(nodeId, metrics.getOrCreate(roundsSinceLastJudgeConfig));
    }

    /**
     * Remove the metrics for a node.
     *
     * @param nodeId the id of the node
     */
    public void removeMetricsForNode(@NonNull final NodeId nodeId) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");

        roundsSinceLastConsensusEvent.remove(nodeId);
        metrics.remove(new RunningAverageMetric.Config(CATEGORY, ROUNDS_SINCE_LAST_CONSENSUS_EVENT + nodeId));

        // Temporarily disabled until we properly detect judges in a round
        //        roundsSinceLastJudge.remove(nodeId);
        //        metrics.remove(new RunningAverageMetric.Config(CATEGORY, ROUNDS_SINCE_LAST_JUDGE + nodeId));
    }

    /**
     * Get the metric that tracks the number of rounds since the last consensus event was observed from a node.
     *
     * @param id the id of the node
     * @return the metric
     * @throws NoSuchElementException if no metric for the node is found
     */
    public @NonNull RunningAverageMetric getRoundsSinceLastConsensusEventMetric(@NonNull final NodeId id) {
        Objects.requireNonNull(id, "id must not be null");
        final RunningAverageMetric metric = roundsSinceLastConsensusEvent.get(id);
        if (metric == null) {
            throw new NoSuchElementException("No metric for node " + id + " found.");
        }
        return metric;
    }

    /**
     * Get the metric that tracks the number of rounds since the last judge was observed from a node.
     *
     * @param id the id of the node
     * @return the metric
     * @throws NoSuchElementException if no metric for the node is found
     */
    public @NonNull RunningAverageMetric getRoundsSinceLastJudgeMetric(@NonNull final NodeId id) {
        Objects.requireNonNull(id, "id must not be null");
        final RunningAverageMetric metric = roundsSinceLastJudge.get(id);
        if (metric == null) {
            throw new NoSuchElementException("No metric for node " + id + " found.");
        }
        return metric;
    }

    /**
     * Get the metric that tracks the fraction of the network that is alive.
     *
     * @return the metric
     */
    public @NonNull RunningAverageMetric getHealthyNetworkFraction() {
        return healthyNetworkFraction;
    }

    /**
     * Get the metric that tracks the time required to compute uptime information each round.
     *
     * @return the metric
     */
    public @NonNull RunningAverageMetric getUptimeComputationTimeMetric() {
        return uptimeComputationTime;
    }
}
