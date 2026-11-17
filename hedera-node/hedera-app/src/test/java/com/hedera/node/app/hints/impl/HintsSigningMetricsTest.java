// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.node.app.utils.TestUtils;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.metrics.api.Metric.ValueType;
import com.swirlds.metrics.api.Metrics;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class HintsSigningMetricsTest {
    private static final String CATEGORY = "app";
    private static final String LATENCY = "hintsSubmitToProofReadyUs";
    private static final List<String> PERCENTILE_GAUGES =
            List.of(LATENCY + "_p50", LATENCY + "_p90", LATENCY + "_p99", LATENCY + "_p99_9", LATENCY + "_max");
    /** The default metrics snapshot interval, after which the latency window advances. */
    private static final Duration SNAPSHOT_INTERVAL = Duration.ofSeconds(3);

    private final Metrics metrics = TestUtils.metrics();
    private final FakeTime time = new FakeTime();

    @Test
    void registersPercentileGaugesWithPlatformConfig() {
        new HintsSigningMetrics(metrics, HederaTestConfigBuilder.createConfig(), time);

        for (final var name : PERCENTILE_GAUGES) {
            assertNotNull(metrics.getMetric(CATEGORY, name), name + " should be registered");
        }
        assertTrue(
                metrics.getMetric(CATEGORY, LATENCY + "_p99").getDescription().startsWith("p99 of latency (µs)"));
    }

    @Test
    void reportsSubmitToProofReadyPercentilesInMicros() {
        final var subject = new HintsSigningMetrics(metrics, HederaTestConfigBuilder.createConfig(), time);

        subject.recordSubmitToProofReady(Duration.ofMillis(2).toNanos());
        subject.recordSubmitToProofReady(Duration.ofMillis(2).toNanos());
        time.tick(SNAPSHOT_INTERVAL);

        // Values are bucket upper bounds within 2^-7 of the exact value
        final double p99 = valueOf(LATENCY + "_p99");
        assertTrue(p99 >= 2_000.0 && p99 <= 2_000.0 * (1 + 1.0 / 128), "p99 should be ~2000 µs, was " + p99);
        final double max = valueOf(LATENCY + "_max");
        assertTrue(max >= 2_000.0 && max <= 2_000.0 * (1 + 1.0 / 128), "max should be ~2000 µs, was " + max);
        assertEquals(2_000.0, valueOf("hintsSubmitToProofReadyUsAvg"), 1e-9);
    }

    @Test
    void omitsPercentileGaugesWithoutPlatformConfig() {
        final var subject = new HintsSigningMetrics(metrics, null, time);

        for (final var name : PERCENTILE_GAUGES) {
            assertNull(metrics.getMetric(CATEGORY, name), name + " should not be registered");
        }
        assertDoesNotThrow(() -> subject.recordSubmitToProofReady(1_000L));
        assertNotNull(metrics.getMetric(CATEGORY, "hintsSubmitToProofReadyUsAvg"));
    }

    @Test
    void omitsPercentileGaugesWithoutMetricsSettings() {
        final var withoutMetricsConfig = ConfigurationBuilder.create().build();

        final var subject = assertDoesNotThrow(() -> new HintsSigningMetrics(metrics, withoutMetricsConfig, time));

        for (final var name : PERCENTILE_GAUGES) {
            assertNull(metrics.getMetric(CATEGORY, name), name + " should not be registered");
        }
        assertDoesNotThrow(() -> subject.recordSubmitToProofReady(1_000L));
    }

    @Test
    void omitsPercentileGaugesIfSnapshotIntervalExceedsTheirWindow() {
        final var slowSnapshots = HederaTestConfigBuilder.create()
                .withValue("metrics.csvWriteFrequency", Duration.ofMinutes(2).toMillis())
                .getOrCreateConfig();

        assertDoesNotThrow(() -> new HintsSigningMetrics(metrics, slowSnapshots, time));

        for (final var name : PERCENTILE_GAUGES) {
            assertNull(metrics.getMetric(CATEGORY, name), name + " should not be registered");
        }
    }

    @Test
    void recordsNanosAsMicrosInAveragesAndCountsEarlyEvents() {
        final var subject = new HintsSigningMetrics(metrics, null, time);

        subject.recordSubmitToFirstPeerSignature(1_500_000L);
        subject.recordPeerSignatureBeforeSubmit(3_000_000L);
        subject.recordThresholdBeforeSubmit();

        assertEquals(1_500.0, valueOf("hintsSubmitToFirstPeerSigUsAvg"), 1e-9);
        assertEquals(3_000.0, valueOf("hintsPeerLeadUsAvg"), 1e-9);
        assertEquals(1L, metrics.getMetric(CATEGORY, "hintsPeerSigBeforeSubmit").get(ValueType.VALUE));
        assertEquals(
                1L, metrics.getMetric(CATEGORY, "hintsThresholdBeforeSubmit").get(ValueType.VALUE));
    }

    private double valueOf(final String name) {
        return ((Number) metrics.getMetric(CATEGORY, name).get(ValueType.VALUE)).doubleValue();
    }
}
