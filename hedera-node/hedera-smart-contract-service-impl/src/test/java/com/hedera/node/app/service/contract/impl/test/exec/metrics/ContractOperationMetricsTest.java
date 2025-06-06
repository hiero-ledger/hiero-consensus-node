// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.within;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractOperationMetrics;
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
class ContractOperationMetricsTest {

    private static final long DEFAULT_NODE_ID = 3;
    private Metrics metrics;
    private ContractOperationMetrics subject;

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

        subject = new ContractOperationMetrics(metrics);
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
        final double average = subject.getAverageSystemContractDuration(method1);
        assertThat(average).isCloseTo(150.0, within(0.5)); // (100 + 200) / 2
    }

    @Test
    void returnsZeroForNonExistentMethod() {
        // Given
        final SystemContractMethod nonExistentMethod = method2;

        // When
        final double duration = subject.getAverageSystemContractDuration(nonExistentMethod);

        // Then
        assertThat(duration).isZero();
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
        assertThat(subject.getAverageSystemContractDuration(method1)).isCloseTo(200.0, within(0.5));
        assertThat(subject.getAverageSystemContractDuration(method2)).isCloseTo(200.0, within(0.5));
    }
}
