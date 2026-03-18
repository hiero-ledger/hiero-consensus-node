// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.within;

import com.hedera.node.app.service.contract.impl.exec.metrics.SystemContractOpsDurationMetric;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import org.hiero.consensus.metrics.config.MetricsConfig;
import org.hiero.consensus.metrics.platform.DefaultPlatformMetrics;
import org.hiero.consensus.metrics.platform.MetricKeyRegistry;
import org.hiero.consensus.metrics.platform.PlatformMetricsFactoryImpl;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SystemContractOpsDurationMetricTest {
    private static final String SC1 = "sc1";
    private static final String ADDR1 = "cafebabe";

    private static final String SC2 = "sc2";
    private static final String ADDR2 = "babecafe";

    private SystemContractOpsDurationMetric subject;

    @BeforeEach
    void setUp() {
        final MetricsConfig metricsConfig =
                HederaTestConfigBuilder.createConfig().getConfigData(MetricsConfig.class);

        final var metrics = new DefaultPlatformMetrics(
                NodeId.of(3),
                new MetricKeyRegistry(),
                Executors.newSingleThreadScheduledExecutor(),
                new PlatformMetricsFactoryImpl(metricsConfig),
                metricsConfig);

        subject = new SystemContractOpsDurationMetric(metrics);
    }

    private static void generateMetric(
            @NonNull final SystemContractOpsDurationMetric subject,
            @NonNull final String systemContractName,
            @NonNull final String systemContractAddress,
            final int cycles,
            final long avg) {
        for (int i = 0; i < cycles; i++) {
            final long shift = ThreadLocalRandom.current().nextLong(avg);
            subject.recordOperationDuration(systemContractName, systemContractAddress, avg + shift);
            subject.recordOperationDuration(systemContractName, systemContractAddress, avg - shift);
        }
    }

    @Test
    void recordsAndRetrievesOperationDuration() {
        // Given
        final int cycles = 1000;
        final long avg = 150;
        // When
        generateMetric(subject, SC1, ADDR1, cycles, avg);
        final double average =
                subject.getSystemContractOpsDuration(SC1, ADDR1).average().get();
        final double count =
                subject.getSystemContractOpsDuration(SC1, ADDR1).counter().get();
        final double total =
                subject.getSystemContractOpsDuration(SC1, ADDR1).accumulator().get();
        // Then
        assertThat(average).isCloseTo(avg, within(5.0));
        assertThat(count).isEqualTo(cycles * 2);
        assertThat(total).isEqualTo(cycles * 2 * avg);
    }

    @Test
    void returnsZeroForNonExistentMethod() {
        final var metric = subject.getSystemContractOpsDuration(SC2, ADDR2);
        assertThat(metric.average().get()).isZero();
        assertThat(metric.accumulator().get()).isZero();
        assertThat(metric.counter().get()).isZero();
    }

    @Test
    void handlesMultipleMethods() {
        // Given
        final int cycles1 = 1000;
        final long avg1 = 150;
        final int cycles2 = 1500;
        final long avg2 = 200;
        // When
        generateMetric(subject, SC1, ADDR1, cycles1, avg1);
        generateMetric(subject, SC2, ADDR2, cycles2, avg2);
        final var metric1 = subject.getSystemContractOpsDuration(SC1, ADDR1);
        final var metric2 = subject.getSystemContractOpsDuration(SC2, ADDR2);
        // Then
        assertThat(metric1.average().get()).isCloseTo(avg1, within(5.0));
        assertThat(metric1.counter().get()).isEqualTo(cycles1 * 2);
        assertThat(metric1.accumulator().get()).isEqualTo(cycles1 * 2 * avg1);
        assertThat(metric2.average().get()).isCloseTo(avg2, within(5.0));
        assertThat(metric2.counter().get()).isEqualTo(cycles2 * 2);
        assertThat(metric2.accumulator().get()).isEqualTo(cycles2 * 2 * avg2);
    }
}
