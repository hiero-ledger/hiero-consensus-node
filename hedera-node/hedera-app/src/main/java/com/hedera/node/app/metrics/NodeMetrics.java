// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.metrics;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.metrics.api.DoubleGauge;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Singleton
public class NodeMetrics {
    private static final String APP_CATEGORY = "app_";
    private static final Logger log = LogManager.getLogger(NodeMetrics.class);
    private final Map<Long, RunningAverageMetric> activeRoundsAverages = new ConcurrentHashMap<>();
    private final Map<Long, DoubleGauge> activeRoundsSnapshots = new ConcurrentHashMap<>();
    private final Metrics metrics;

    @Inject
    public NodeMetrics(@NonNull final Metrics metrics) {
        this.metrics = requireNonNull(metrics);
    }

    /**
     * Registers the metrics for the active round % for each node in the given roster.
     *
     * @param rosterEntries the list of roster entries
     */
    public void registerNodeMetrics(@NonNull List<RosterEntry> rosterEntries) {
        for (final var entry : rosterEntries) {
            final var nodeId = entry.nodeId();
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
}
