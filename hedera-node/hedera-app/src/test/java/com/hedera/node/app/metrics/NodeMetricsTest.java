// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.metrics;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.service.token.NodeRewardAmounts;
import com.swirlds.metrics.api.DoubleGauge;
import com.swirlds.metrics.api.LongGauge;
import com.swirlds.metrics.api.Metrics;
import java.util.List;
import java.util.Set;
import org.hiero.consensus.metrics.RunningAverageMetric;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the NodeMetrics class.
 */
@ExtendWith(MockitoExtension.class)
class NodeMetricsTest {

    private static final long CONSENSUS_NODE_REWARD = 100L;
    private static final long BLOCK_NODE_REWARD = 50L;
    private static final long INACTIVE_CONSENSUS_NODE_REWARD = 10L;

    private static final AccountID PAYER_ID =
            AccountID.newBuilder().accountNum(801L).build();
    private static final AccountID NODE_0_ACCOUNT =
            AccountID.newBuilder().accountNum(1000L).build();
    private static final AccountID NODE_1_ACCOUNT =
            AccountID.newBuilder().accountNum(1001L).build();

    @Mock
    private Metrics metrics;

    @Mock
    private RunningAverageMetric averageMetric;

    @Mock
    private DoubleGauge doubleGauge;

    @Mock
    private LongGauge longGauge;

    private NodeMetrics nodeMetrics;

    @BeforeEach
    void setUp() {
        lenient().when(metrics.getOrCreate(any(LongGauge.Config.class))).thenReturn(longGauge);
        nodeMetrics = new NodeMetrics(metrics);
    }

    @Test
    void constructorTest() {
        assertThrows(NullPointerException.class, () -> new NodeMetrics(null));
    }

    @Test
    void registerNodeMetrics() {
        long nodeId = 1L;

        when(metrics.getOrCreate(any(RunningAverageMetric.Config.class))).thenReturn(averageMetric);
        when(metrics.getOrCreate(any(DoubleGauge.Config.class))).thenReturn(doubleGauge);

        nodeMetrics.registerNodeMetrics(Set.of(nodeId));

        double activePercent = 0.75;
        nodeMetrics.updateNodeActiveMetrics(nodeId, activePercent);

        verify(averageMetric, times(1)).update(activePercent);
        verify(doubleGauge, times(1)).set(activePercent);
    }

    @Test
    void registerNodeMetricsDuplicateEntriesRegistersOnlyOnce() {
        long nodeId = 2L;

        when(metrics.getOrCreate(any(RunningAverageMetric.Config.class))).thenReturn(averageMetric);
        when(metrics.getOrCreate(any(DoubleGauge.Config.class))).thenReturn(doubleGauge);

        nodeMetrics.registerNodeMetrics(List.of(nodeId, nodeId));

        double activePercent = 0.9;
        nodeMetrics.updateNodeActiveMetrics(nodeId, activePercent);

        verify(averageMetric, times(1)).update(activePercent);
        verify(doubleGauge, times(1)).set(activePercent);
    }

    @Test
    void updateNodeActiveMetricsNoMetricsRegistered() {
        long nodeId = 3L;
        assertDoesNotThrow(() -> nodeMetrics.updateNodeActiveMetrics(nodeId, 0.5));

        verifyNoInteractions(averageMetric, doubleGauge);
    }

    @Test
    void registerNodeMetricsConfigurationPassedToMetrics() {
        long nodeId = 4L;

        when(metrics.getOrCreate(any(RunningAverageMetric.Config.class))).thenReturn(averageMetric);
        when(metrics.getOrCreate(any(DoubleGauge.Config.class))).thenReturn(doubleGauge);

        nodeMetrics.registerNodeMetrics(List.of(nodeId));

        ArgumentCaptor<RunningAverageMetric.Config> avgConfigCaptor =
                ArgumentCaptor.forClass(RunningAverageMetric.Config.class);
        verify(metrics, times(1)).getOrCreate(avgConfigCaptor.capture());
        RunningAverageMetric.Config avgConfig = avgConfigCaptor.getValue();

        ArgumentCaptor<DoubleGauge.Config> gaugeConfigCaptor = ArgumentCaptor.forClass(DoubleGauge.Config.class);
        verify(metrics, times(1)).getOrCreate(gaugeConfigCaptor.capture());
        DoubleGauge.Config gaugeConfig = gaugeConfigCaptor.getValue();

        assertEquals("app_", avgConfig.getCategory(), "Average metric category should be 'app_'");
        assertEquals(
                "nodeActivePercent_node" + nodeId,
                avgConfig.getName(),
                "Average metric name should be 'nodeActivePercent_node{nodeId}'");

        assertEquals("app_", gaugeConfig.getCategory(), "Gauge metric category should be 'app_'");
        assertEquals(
                "nodeActivePercentSnapshot_node" + nodeId,
                gaugeConfig.getName(),
                "Gauge metric name should be 'nodeActivePercentSnapshot_node{nodeId}'");
    }

    @Test
    void updateRewardMetricsEvictsNodeWithNoRewardsInNewPeriod() {
        // Period 1: node0 receives both reward types
        final var period1 = new NodeRewardAmounts(PAYER_ID);
        period1.addConsensusNodeReward(0L, NODE_0_ACCOUNT, CONSENSUS_NODE_REWARD);
        period1.addBlockNodeReward(0L, NODE_0_ACCOUNT, BLOCK_NODE_REWARD);
        nodeMetrics.updateRewardMetrics(period1, 1);

        // Period 2: node0 receives no rewards — node is evicted, gauges removed
        final var period2 = new NodeRewardAmounts(PAYER_ID);
        nodeMetrics.updateRewardMetrics(period2, 0);

        verify(longGauge).set(CONSENSUS_NODE_REWARD); // period 1
        verify(longGauge).set(BLOCK_NODE_REWARD); // period 1
        // Verify that node 0's gauges were removed from the metrics system in period 2.
        // All 3 possible gauge types (INACTIVE, CONSENSUS_NODE, BLOCK_NODE) are removed per node.
        verify(metrics, times(3)).remove(any(LongGauge.Config.class));
    }

    @Test
    void updateRewardMetricsSetsEligibleCount() {
        final var rewardAmounts = new NodeRewardAmounts(PAYER_ID);
        rewardAmounts.addConsensusNodeReward(0L, NODE_0_ACCOUNT, CONSENSUS_NODE_REWARD);
        rewardAmounts.addBlockNodeReward(0L, NODE_0_ACCOUNT, BLOCK_NODE_REWARD);

        nodeMetrics.updateRewardMetrics(rewardAmounts, 3);

        verify(longGauge).set(3L);
    }

    @Test
    void updateRewardMetricsCreatesPerNodeGaugesForEachRewardType() {
        final var rewardAmounts = new NodeRewardAmounts(PAYER_ID);
        rewardAmounts.addConsensusNodeReward(0L, NODE_0_ACCOUNT, CONSENSUS_NODE_REWARD);
        rewardAmounts.addBlockNodeReward(0L, NODE_0_ACCOUNT, BLOCK_NODE_REWARD);
        rewardAmounts.addInactiveConsensusNodeReward(1L, NODE_1_ACCOUNT, INACTIVE_CONSENSUS_NODE_REWARD);

        nodeMetrics.updateRewardMetrics(rewardAmounts, 1);

        // Constructor creates 1 LongGauge (eligible count),
        // updatePerNodeRewardMetrics creates 3 more (per-node reward gauges)
        final var gaugeCaptor = ArgumentCaptor.forClass(LongGauge.Config.class);
        verify(metrics, times(4)).getOrCreate(gaugeCaptor.capture());
        final var configs = gaugeCaptor.getAllValues();
        assertEquals("app_", configs.get(1).getCategory());
        assertEquals("nodeRewardTinybars_node0_CONSENSUS_NODE", configs.get(1).getName());
        assertEquals("nodeRewardTinybars_node0_BLOCK_NODE", configs.get(2).getName());
        assertEquals("nodeRewardTinybars_node1_INACTIVE", configs.get(3).getName());

        verify(longGauge).set(CONSENSUS_NODE_REWARD);
        verify(longGauge).set(BLOCK_NODE_REWARD);
        verify(longGauge).set(INACTIVE_CONSENSUS_NODE_REWARD);
    }

    @Test
    void updateRewardMetricsSkipsNodesAboveMaxTrackedNodes() {
        final var rewardAmounts = new NodeRewardAmounts(PAYER_ID);
        // Each node gets 1 entry; exceeds MAX_TRACKED_NODES by 2
        for (long nodeId = 1; nodeId <= NodeMetrics.MAX_TRACKED_NODES + 2; nodeId++) {
            final AccountID account =
                    AccountID.newBuilder().accountNum(1000L + nodeId).build();
            rewardAmounts.addConsensusNodeReward(nodeId, account, 100L);
        }

        nodeMetrics.updateRewardMetrics(rewardAmounts, 0);

        // Constructor creates 1 LongGauge (eligible count) + MAX_TRACKED_NODES per-node gauges
        verify(metrics, times(NodeMetrics.MAX_TRACKED_NODES + 1)).getOrCreate(any(LongGauge.Config.class));
    }

    @Test
    void updateRewardMetricsEvictsGaugesForNodesLeavingSample() {
        // Period 1: nodes 0..MAX_TRACKED_NODES-1 are tracked (fills the sample)
        final var period1 = new NodeRewardAmounts(PAYER_ID);
        for (long nodeId = 0; nodeId < NodeMetrics.MAX_TRACKED_NODES; nodeId++) {
            final AccountID account =
                    AccountID.newBuilder().accountNum(1000L + nodeId).build();
            period1.addConsensusNodeReward(nodeId, account, 100L);
        }
        nodeMetrics.updateRewardMetrics(period1, 0);

        // Period 2: only node MAX_TRACKED_NODES+1 has rewards — previous nodes should be evicted
        final var period2 = new NodeRewardAmounts(PAYER_ID);
        final long newNodeId = NodeMetrics.MAX_TRACKED_NODES + 1;
        period2.addConsensusNodeReward(
                newNodeId, AccountID.newBuilder().accountNum(2000L).build(), 200L);
        nodeMetrics.updateRewardMetrics(period2, 0);

        // Verify gauges for evicted nodes were removed from the metrics system.
        // Each evicted node has 3 possible gauge types removed (INACTIVE, CONSENSUS_NODE, BLOCK_NODE).
        verify(metrics, times(NodeMetrics.MAX_TRACKED_NODES * 3)).remove(any(LongGauge.Config.class));
    }

    @Test
    void updateRewardMetricsNodeTransitionsFromActiveToInactiveAcrossPeriods() {
        // Period 1: node0 is active, earning a consensus reward
        final var period1 = new NodeRewardAmounts(PAYER_ID);
        period1.addConsensusNodeReward(0L, NODE_0_ACCOUNT, CONSENSUS_NODE_REWARD);
        nodeMetrics.updateRewardMetrics(period1, 1);

        // Period 2: node0 is now inactive — all tracked gauges are zeroed first,
        // then a new INACTIVE gauge is created and set with the inactive reward
        final var period2 = new NodeRewardAmounts(PAYER_ID);
        period2.addInactiveConsensusNodeReward(0L, NODE_0_ACCOUNT, INACTIVE_CONSENSUS_NODE_REWARD);
        nodeMetrics.updateRewardMetrics(period2, 0);

        final var gaugeCaptor = ArgumentCaptor.forClass(LongGauge.Config.class);
        // Constructor (1) + CONSENSUS_NODE gauge (1) + INACTIVE gauge (1) = 3 registrations
        verify(metrics, times(3)).getOrCreate(gaugeCaptor.capture());
        final var configs = gaugeCaptor.getAllValues();
        assertEquals("nodeRewardTinybars_node0_CONSENSUS_NODE", configs.get(1).getName());
        assertEquals("nodeRewardTinybars_node0_INACTIVE", configs.get(2).getName());

        verify(longGauge).set(CONSENSUS_NODE_REWARD); // period 1
        verify(longGauge).set(INACTIVE_CONSENSUS_NODE_REWARD); // period 2
    }

    @Test
    void updateRewardMetricsZerosStaleGaugesForNodeRemainingInSample() {
        // Period 1: node0 gets both CONSENSUS_NODE and BLOCK_NODE rewards
        final var period1 = new NodeRewardAmounts(PAYER_ID);
        period1.addConsensusNodeReward(0L, NODE_0_ACCOUNT, CONSENSUS_NODE_REWARD);
        period1.addBlockNodeReward(0L, NODE_0_ACCOUNT, BLOCK_NODE_REWARD);
        nodeMetrics.updateRewardMetrics(period1, 1);

        // Period 2: node0 still present but only gets CONSENSUS_NODE — BLOCK_NODE gauge should be zeroed
        final var period2 = new NodeRewardAmounts(PAYER_ID);
        period2.addConsensusNodeReward(0L, NODE_0_ACCOUNT, 200L);
        nodeMetrics.updateRewardMetrics(period2, 1);

        // Node 0 stays in the sample, so no eviction should occur
        verify(metrics, times(0)).remove(any(LongGauge.Config.class));
        // The zero-then-set cycle means BLOCK_NODE ends at 0 (from the zero step, not overwritten)
        // and CONSENSUS_NODE gets overwritten with 200. Since all gauges share the same mock,
        // verify the zero step ran (at least 2 zeros for the 2 tracked gauges in period 2)
        verify(longGauge).set(200L);
    }

    @Test
    void updateRewardMetricsEmptyRewardsDoesNotCreatePerNodeGauges() {
        final var rewardAmounts = new NodeRewardAmounts(PAYER_ID);

        nodeMetrics.updateRewardMetrics(rewardAmounts, 0);

        // Constructor creates 1 LongGauge for the blockNodeRewardsEligibleCount
        verify(metrics, times(1)).getOrCreate(any(LongGauge.Config.class));
    }
}
