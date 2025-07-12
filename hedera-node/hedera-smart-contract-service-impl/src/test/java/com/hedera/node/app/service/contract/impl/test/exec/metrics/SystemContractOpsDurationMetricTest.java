// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.within;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.service.contract.impl.exec.metrics.SystemContractOpsDurationMetric;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SystemContractOpsDurationMetricTest {

    private static final long DEFAULT_NODE_ID = 3;
    private Metrics metrics;
    private SystemContractOpsDurationMetric subject;

    @Mock
    private SystemContractMethod method1;

    @Mock
    private SystemContractMethod method2;

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

        subject = new SystemContractOpsDurationMetric(metrics);
    }

    @Test
    void recordsAndRetrievesOperationDuration() {
        // Given
        final long duration1 = 100L;
        final long duration2 = 200L;

        // When
        subject.recordOperationDuration(method1, duration1);
        subject.recordOperationDuration(method1, duration2);

        // Then
        final double average = subject.getAverageSystemContractOpsDuration(method1);

        final double count = subject.getSystemContractOpsDurationCount(method1);
        final double total = subject.getSystemContractOpsTotalDuration(method1);
        assertThat(average).isCloseTo(150.0, within(5.0)); // (100 + 200) / 2
        assertThat(count).isEqualTo(2.0); // Two durations recorded
        assertThat(total).isEqualTo(300.0); // 100 + 200
    }

    @Test
    void returnsZeroForNonExistentMethod() {
        // Given
        final SystemContractMethod nonExistentMethod = method2;

        // When
        final double duration = subject.getAverageSystemContractOpsDuration(nonExistentMethod);
        final double count = subject.getSystemContractOpsDurationCount(nonExistentMethod);
        final double total = subject.getSystemContractOpsTotalDuration(nonExistentMethod);

        // Then
        assertThat(duration).isZero();
        assertThat(count).isZero();
        assertThat(total).isZero();
    }

    @Test
    void handlesMultipleMethods() {
        // Given
        final long duration1 = 100L;
        final long duration2 = 200L;
        final long duration3 = 300L;
        given(method1.methodName()).willReturn("method1");
        given(method2.methodName()).willReturn("method2");

        // When
        subject.recordOperationDuration(method1, duration1);
        subject.recordOperationDuration(method1, duration3);
        subject.recordOperationDuration(method1, duration1);
        subject.recordOperationDuration(method1, duration3);
        subject.recordOperationDuration(method2, duration2);
        subject.recordOperationDuration(method2, duration2);

        // Then
        assertThat(subject.getAverageSystemContractOpsDuration(method1)).isCloseTo(200.0, within(5.0));
        assertThat(subject.getSystemContractOpsDurationCount(method1)).isEqualTo(4);
        assertThat(subject.getSystemContractOpsTotalDuration(method1)).isEqualTo(800L); // 100 + 300 + 100 + 300
        assertThat(subject.getAverageSystemContractOpsDuration(method2)).isCloseTo(200.0, within(5.0));
        assertThat(subject.getSystemContractOpsDurationCount(method2)).isEqualTo(2);
        assertThat(subject.getSystemContractOpsTotalDuration(method2)).isEqualTo(400L); // 200 + 200
    }
}
