// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.within;

import com.hedera.node.app.service.contract.impl.exec.metrics.PrecompileMetrics;
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

class PrecompileMetricsTest {

    private static final long DEFAULT_NODE_ID = 3;
    private Metrics metrics;
    private PrecompileMetrics subject;

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

        subject = new PrecompileMetrics(metrics);
    }

    @Test
    void recordsAndRetrievesPrecompileDuration() {
        // Given
        final String precompileName = "testPrecompile";
        final long duration1 = 100L;
        final long duration2 = 200L;

        // When
        subject.recordPrecompileDuration(precompileName, duration1);
        subject.recordPrecompileDuration(precompileName, duration2);

        // Then
        final double average = subject.getAveragePrecompileDuration(precompileName);
        assertThat(average).isCloseTo(150.0, within(0.5)); // (100 + 200) / 2
    }

    @Test
    void returnsZeroForNonExistentPrecompile() {
        // Given
        final String nonExistentPrecompile = "nonExistent";

        // When
        final double duration = subject.getAveragePrecompileDuration(nonExistentPrecompile);

        // Then
        assertThat(duration).isZero();
    }

    @Test
    void handlesMultiplePrecompiles() {
        // Given
        final String precompile1 = "precompile1";
        final String precompile2 = "precompile2";
        final long duration1 = 100L;
        final long duration2 = 200L;

        // When
        subject.recordPrecompileDuration(precompile1, duration1);
        subject.recordPrecompileDuration(precompile2, duration2);

        // Then
        assertThat(subject.getAveragePrecompileDuration(precompile1)).isEqualTo(100.0);
        assertThat(subject.getAveragePrecompileDuration(precompile2)).isEqualTo(200.0);
    }
}
