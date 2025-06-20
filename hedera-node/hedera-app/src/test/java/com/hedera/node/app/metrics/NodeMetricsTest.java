// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.metrics;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.metrics.api.DoubleGauge;
import com.swirlds.metrics.api.Metrics;
import java.util.List;
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
    @Mock
    private Metrics metrics;

    @Mock
    private RunningAverageMetric averageMetric;

    @Mock
    private DoubleGauge doubleGauge;

    private NodeMetrics nodeMetrics;

    @BeforeEach
    void setUp() {
        nodeMetrics = new NodeMetrics(metrics);
    }

    @Test
    void constructorTest() {
        assertThrows(NullPointerException.class, () -> new NodeMetrics(null));
    }

    @Test
    void registerNodeMetrics() {
        long nodeId = 1L;
        RosterEntry entry = new RosterEntry(nodeId, 0, Bytes.EMPTY, List.of());
        List<RosterEntry> roster = List.of(entry);

        when(metrics.getOrCreate(any(RunningAverageMetric.Config.class))).thenReturn(averageMetric);
        when(metrics.getOrCreate(any(DoubleGauge.Config.class))).thenReturn(doubleGauge);

        nodeMetrics.registerNodeMetrics(roster);

        double activePercent = 0.75;
        nodeMetrics.updateNodeActiveMetrics(nodeId, activePercent);

        verify(averageMetric, times(1)).update(activePercent);
        verify(doubleGauge, times(1)).set(activePercent);
    }

    @Test
    void registerNodeMetricsDuplicateEntriesRegistersOnlyOnce() {
        long nodeId = 2L;
        RosterEntry entry1 = new RosterEntry(nodeId, 0, Bytes.EMPTY, List.of());
        RosterEntry entry2 = new RosterEntry(nodeId, 0, Bytes.EMPTY, List.of());
        List<RosterEntry> roster = List.of(entry1, entry2);

        when(metrics.getOrCreate(any(RunningAverageMetric.Config.class))).thenReturn(averageMetric);
        when(metrics.getOrCreate(any(DoubleGauge.Config.class))).thenReturn(doubleGauge);

        nodeMetrics.registerNodeMetrics(roster);

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
        RosterEntry entry = new RosterEntry(nodeId, 0, Bytes.EMPTY, List.of());
        List<RosterEntry> roster = List.of(entry);

        when(metrics.getOrCreate(any(RunningAverageMetric.Config.class))).thenReturn(averageMetric);
        when(metrics.getOrCreate(any(DoubleGauge.Config.class))).thenReturn(doubleGauge);

        nodeMetrics.registerNodeMetrics(roster);

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
}
