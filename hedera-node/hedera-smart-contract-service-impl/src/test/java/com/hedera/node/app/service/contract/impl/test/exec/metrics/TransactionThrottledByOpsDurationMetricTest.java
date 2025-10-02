// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.app.service.contract.impl.exec.metrics.TransactionThrottledByOpsDurationMetric;
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

class TransactionThrottledByOpsDurationMetricTest {
    private static final long DEFAULT_NODE_ID = 3;
    private Metrics metrics;
    private TransactionThrottledByOpsDurationMetric subject;

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

        subject = new TransactionThrottledByOpsDurationMetric(metrics);
    }

    @Test
    void incrementIncreasesCount() {
        // Initially, the counter should be 0
        assertThat(subject.getCount()).isZero();

        // Increment once
        subject.increment();
        assertThat(subject.getCount()).isEqualTo(1);

        // Increment two more times
        subject.increment();
        subject.increment();
        assertThat(subject.getCount()).isEqualTo(3);
    }
}
