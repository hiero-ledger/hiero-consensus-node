// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.hiero.metrics.api.core.MetricKey;
import org.hiero.metrics.api.core.MetricType;
import org.hiero.metrics.api.stat.StatUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class LongCounterTest extends AbstractStatefulMetricBaseTest<LongCounter, LongCounter.Builder> {

    @Override
    protected MetricType metricType() {
        return MetricType.COUNTER;
    }

    @Override
    protected Class<LongCounter> metricClassType() {
        return LongCounter.class;
    }

    @Override
    protected LongCounter.Builder emptyMetricBuilder(String name) {
        return LongCounter.builder(name);
    }

    @Test
    void testNullNameMetricKey() {
        assertThatThrownBy(() -> LongCounter.key(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testNullMetricKeyBuilder() {
        assertThatThrownBy(() -> LongCounter.builder((MetricKey<LongCounter>) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("key must not be null");
    }

    @Test
    void testNullMetricNameBuilder() {
        assertThatThrownBy(() -> LongCounter.builder((String) null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testDefaultInitialValueNoLabels() {
        LongCounter metric = emptyMetricBuilder().build();
        assertThat(metric.getOrCreateNotLabeled().getAsLong()).isEqualTo(0L);

        metric.getOrCreateNotLabeled().increment();
        assertThat(metric.getOrCreateNotLabeled().getAsLong()).isEqualTo(1L);

        metric.reset();
        assertThat(metric.getOrCreateNotLabeled().getAsLong()).isEqualTo(0L);
    }

    @Test
    void testDefaultInitialValueWithLabels() {
        LongCounter metric = emptyMetricBuilder().withDynamicLabelNames("label").build();
        assertThat(metric.getOrCreateLabeled("label", "1").getAsLong()).isEqualTo(0L);

        metric.getOrCreateLabeled("label", "1").increment();
        metric.getOrCreateLabeled("label", "2").increment(10L);
        assertThat(metric.getOrCreateLabeled("label", "1").getAsLong()).isEqualTo(1L);
        assertThat(metric.getOrCreateLabeled("label", "2").getAsLong()).isEqualTo(10L);

        metric.reset();
        assertThat(metric.getOrCreateLabeled("label", "1").getAsLong()).isEqualTo(0L);
        assertThat(metric.getOrCreateLabeled("label", "2").getAsLong()).isEqualTo(0L);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L, 100L, Long.MAX_VALUE})
    void testCustomInitialValueNoLabels(long initialValue) {
        LongCounter metric = emptyMetricBuilder().withInitValue(initialValue).build();
        assertThat(metric.getOrCreateNotLabeled().getAsLong()).isEqualTo(initialValue);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L, 100L, Long.MAX_VALUE})
    void testCustomInitializerNoLabels(long initialValue) {
        LongCounter metric =
                emptyMetricBuilder().withDefaultInitializer(() -> initialValue).build();
        assertThat(metric.getOrCreateNotLabeled().getAsLong()).isEqualTo(initialValue);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L, 100L, Long.MAX_VALUE - 100L})
    void testCustomInitialValueWithLabels(long initialValue) {
        LongCounter metric = emptyMetricBuilder()
                .withDynamicLabelNames("label")
                .withInitValue(initialValue)
                .build();

        assertThat(metric.getOrCreateLabeled("label", "1").getAsLong()).isEqualTo(initialValue);

        metric.getOrCreateLabeled("label", "1").increment();
        metric.getOrCreateLabeled("label", "2").increment(10L);

        assertThat(metric.getOrCreateLabeled(StatUtils.asInitializer(initialValue), "label", "1")
                        .getAsLong())
                .as("initializer is not used when datapoint already initialized")
                .isNotEqualTo(initialValue);

        metric.reset();
        assertThat(metric.getOrCreateLabeled("label", "1").getAsLong()).isEqualTo(initialValue);
        assertThat(metric.getOrCreateLabeled("label", "2").getAsLong()).isEqualTo(initialValue);
    }
}
