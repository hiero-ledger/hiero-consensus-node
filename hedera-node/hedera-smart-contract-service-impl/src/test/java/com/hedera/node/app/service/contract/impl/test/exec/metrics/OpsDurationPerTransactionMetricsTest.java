// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.app.service.contract.impl.exec.metrics.OpsDurationPerTransactionMetrics;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.common.metrics.platform.DefaultPlatformMetrics;
import com.swirlds.common.metrics.platform.MetricKeyRegistry;
import com.swirlds.common.metrics.platform.PlatformMetricsFactoryImpl;
import com.swirlds.metrics.api.Metrics;
import java.util.concurrent.Executors;
import org.assertj.core.data.Percentage;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OpsDurationPerTransactionMetricsTest {

    private static final long DEFAULT_NODE_ID = 3;
    private Metrics metrics;
    private OpsDurationPerTransactionMetrics subject;

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

        subject = new OpsDurationPerTransactionMetrics(metrics);
    }

    @Test
    void runningAverageIsConstantWithOneSubmittedValue() {
        // Given
        final long duration = 100L;

        // When
        subject.recordTxnTotalOpsDuration(duration);

        // Then
        assertThat(subject.get()).isEqualTo(duration);
    }

    @Test
    void runningAverageIsAnActualAverage() {
        // Given
        final long duration1 = 100L;
        final long duration2 = 200L;

        // When
        subject.recordTxnTotalOpsDuration(duration1);
        subject.recordTxnTotalOpsDuration(duration2);

        // Then
        assertThat(subject.get()).isCloseTo(150, Percentage.withPercentage(1)); // Allow some slack for half-life
    }
}
