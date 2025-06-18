// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.within;

import com.hedera.node.app.service.contract.impl.exec.metrics.OpCodeOpsDurationMetric;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.common.metrics.platform.DefaultPlatformMetrics;
import com.swirlds.common.metrics.platform.MetricKeyRegistry;
import com.swirlds.common.metrics.platform.PlatformMetricsFactoryImpl;
import com.swirlds.metrics.api.Metrics;
import java.util.concurrent.Executors;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpCodeOpsDurationMetricTest {

    private static final long DEFAULT_NODE_ID = 3;
    private Metrics metrics;
    private OpCodeOpsDurationMetric subject;

    @BeforeEach
    void setUp() {
        final MetricsConfig metricsConfig =
                HederaTestConfigBuilder.createConfig().getConfigData(MetricsConfig.class);

        metrics = new DefaultPlatformMetrics(
                NodeId.of(DEFAULT_NODE_ID),
                new MetricKeyRegistry(),
                Executors.newSingleThreadScheduledExecutor(),
                new PlatformMetricsFactoryImpl(metricsConfig),
                metricsConfig);

        subject = new OpCodeOpsDurationMetric(metrics);
    }

    @Test
    void recordsAndRetrievesOperationDuration() {
        // Given
        final int opcode = 1;
        final long duration1 = 100L;
        final long duration2 = 200L;

        // When
        subject.recordOperationDuration(opcode, duration2);
        subject.recordOperationDuration(opcode, duration1);

        // Then
        final double average = subject.getAverageOperationDuration(opcode);
        final long total = subject.getTotalOperationDuration(opcode);
        final long count = subject.getOperationCount(opcode);
        assertThat(average).isCloseTo(150.0, within(0.5)); // (100 + 200) / 2
        assertThat(total).isEqualTo(300L); // 100 + 200
        assertThat(count).isEqualTo(2L); // Two durations recorded
    }

    @Test
    void returnsZeroForNonExistentOpcode() {
        // Given
        final int nonExistentOpcode = 999;

        // When
        final double duration = subject.getAverageOperationDuration(nonExistentOpcode);
        final long total = subject.getTotalOperationDuration(nonExistentOpcode);
        final long count = subject.getOperationCount(nonExistentOpcode);

        // Then
        assertThat(duration).isZero();
        assertThat(total).isZero();
        assertThat(count).isZero();
    }

    @Test
    void handlesMultipleOpcodes() {
        // Given
        final int opcode1 = 1;
        final int opcode2 = 2;
        final long duration1 = 100L;
        final long duration2 = 200L;

        // When
        subject.recordOperationDuration(opcode1, duration1);
        subject.recordOperationDuration(opcode2, duration2);

        // Then
        assertThat(subject.getAverageOperationDuration(opcode1)).isEqualTo(100.0);
        assertThat(subject.getOperationCount(opcode1)).isEqualTo(1);
        assertThat(subject.getTotalOperationDuration(opcode1)).isEqualTo(100L);
        assertThat(subject.getAverageOperationDuration(opcode2)).isEqualTo(200.0);
        assertThat(subject.getOperationCount(opcode2)).isEqualTo(1);
        assertThat(subject.getTotalOperationDuration(opcode2)).isEqualTo(200L);
    }
}
