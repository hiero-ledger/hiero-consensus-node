// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.creation.tipset;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.common.metrics.SpeedometerMetric;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.Map;
import org.hiero.consensus.model.node.NodeId;

/**
 * Encapsulates metrics for the tipset event creator.
 */
public class TipsetMetrics {

    private static final RunningAverageMetric.Config TIPSET_ADVANCEMENT_CONFIG = new RunningAverageMetric.Config(
                    "platform", "tipsetAdvancement")
            .withDescription("The score, based on tipset advancement weight, of each new event created by this "
                    + "node. A score of 0.0 means the an event has zero advancement weight, while a score "
                    + "of 1.0 means that the event had the maximum possible advancement weight.");
    private final RunningAverageMetric tipsetAdvancementMetric;

    private static final RunningAverageMetric.Config SELFISHNESS_CONFIG = new RunningAverageMetric.Config(
                    "platform", "selfishness")
            .withDescription("The score, based on tipset advancements, of how much this node is being selfish. "
                    + "Selfishness is defined as refusing to use another node's events as other parents.");
    private final RunningAverageMetric selfishnessMetric;

    private final Map<NodeId, SpeedometerMetric> tipsetParentMetrics = new HashMap<>();
    private final Map<NodeId, SpeedometerMetric> pityParentMetrics = new HashMap<>();

    /**
     * Create metrics for the tipset event creator.
     *
     * @param platformContext the platform context
     */
    public TipsetMetrics(@NonNull final PlatformContext platformContext, @NonNull final Roster roster) {

        final Metrics metrics = platformContext.getMetrics();
        tipsetAdvancementMetric = metrics.getOrCreate(TIPSET_ADVANCEMENT_CONFIG);
        selfishnessMetric = metrics.getOrCreate(SELFISHNESS_CONFIG);

        for (final RosterEntry address : roster.rosterEntries()) {
            final NodeId nodeId = NodeId.of(address.nodeId());

            final SpeedometerMetric.Config parentConfig = new SpeedometerMetric.Config(
                            "platform", "tipsetParent" + nodeId.id())
                    .withDescription("Cycled when an event from that node is used as a "
                            + "parent because it optimized the tipset advancement weight.");
            final SpeedometerMetric parentMetric = metrics.getOrCreate(parentConfig);
            tipsetParentMetrics.put(nodeId, parentMetric);

            final SpeedometerMetric.Config pityParentConfig = new SpeedometerMetric.Config(
                            "platform", "pityParent" + nodeId.id())
                    .withDescription("Cycled when an event from that node is used as a "
                            + "parent without consideration of tipset advancement weight optimization "
                            + "(i.e. taking 'pity' on a node that isn't getting its events chosen as parents).");
            final SpeedometerMetric pityParentMetric = metrics.getOrCreate(pityParentConfig);
            pityParentMetrics.put(nodeId, pityParentMetric);
        }
    }

    /**
     * Get the metric used to track the tipset score of events created by this node.
     *
     * @return the tipset advancement metric
     */
    @NonNull
    public RunningAverageMetric getTipsetAdvancementMetric() {
        return tipsetAdvancementMetric;
    }

    /**
     * Get the metric used to track the selfishness score of this node.
     *
     * @return the selfishness score metric
     */
    @NonNull
    public RunningAverageMetric getSelfishnessMetric() {
        return selfishnessMetric;
    }

    /**
     * Get the metric used to track the number of times this node has used an event from the given node as a parent
     * because it optimized the tipset score.
     *
     * @param nodeId the node ID
     * @return the parent metric
     */
    @NonNull
    public SpeedometerMetric getTipsetParentMetric(@NonNull final NodeId nodeId) {
        return tipsetParentMetrics.get(nodeId);
    }

    /**
     * Get the metric used to track the number of times this node has used an event from the given node as a parent
     * without consideration of tipset advancement weight optimization.
     *
     * @param nodeId the node ID
     * @return the pity parent metric
     */
    @NonNull
    public SpeedometerMetric getPityParentMetric(@NonNull final NodeId nodeId) {
        return pityParentMetrics.get(nodeId);
    }
}
