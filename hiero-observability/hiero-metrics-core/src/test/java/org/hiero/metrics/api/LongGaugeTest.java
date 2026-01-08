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

public class LongGaugeTest extends AbstractSettableMetricBaseTest<LongGauge, LongGauge.Builder> {

    @Override
    protected MetricType metricType() {
        return MetricType.GAUGE;
    }

    @Override
    protected Class<LongGauge> metricClassType() {
        return LongGauge.class;
    }

    @Override
    protected LongGauge.Builder emptyMetricBuilder(String name) {
        return LongGauge.builder(name);
    }

    @Test
    void testNullNameMetricKey() {
        assertThatThrownBy(() -> LongGauge.key(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testNullMetricKeyBuilder() {
        assertThatThrownBy(() -> LongGauge.builder((MetricKey<LongGauge>) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("key must not be null");
    }

    @Test
    void testNullMetricNameBuilder() {
        assertThatThrownBy(() -> LongGauge.builder((String) null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testWithNullOperatorThrows() {
        LongGauge.Builder builder = emptyMetricBuilder();
        assertThatThrownBy(() -> builder.withOperator(null, false))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Operator must not be null");
    }

    @Test
    void testOperatorDefaultInitialValueNoLabels() {
        LongGauge metric =
                emptyMetricBuilder().withOperator((l, r) -> l + r + 3L, false).build();

        metric.getOrCreateNotLabeled().update(10L);
        assertThat(metric.getOrCreateNotLabeled().getAsLong()).isEqualTo(13L);

        metric.reset();
        assertThat(metric.getOrCreateNotLabeled().getAsLong()).isEqualTo(0L);
    }

    @Test
    void testOperatorDefaultInitialValueWithLabels() {
        LongGauge metric = emptyMetricBuilder()
                .withDynamicLabelNames("label")
                .withOperator((l, r) -> l + r + 3L, false)
                .build();

        metric.getOrCreateLabeled("label", "1").update(10L);
        metric.getOrCreateLabeled("label", "2").update(5L);
        assertThat(metric.getOrCreateLabeled("label", "1").getAsLong()).isEqualTo(13L);
        assertThat(metric.getOrCreateLabeled("label", "2").getAsLong()).isEqualTo(8L);

        metric.reset();
        assertThat(metric.getOrCreateLabeled("label", "1").getAsLong()).isEqualTo(0L);
        assertThat(metric.getOrCreateLabeled("label", "2").getAsLong()).isEqualTo(0L);
    }

    @Test
    void testOperatorDefaultCustomInitialValueNoLabels() {
        LongGauge metric = emptyMetricBuilder()
                .withOperator((l, r) -> l + r + 3L, false)
                .withInitValue(10L)
                .build();

        metric.getOrCreateNotLabeled().update(5L);
        assertThat(metric.getOrCreateNotLabeled().getAsLong()).isEqualTo(18L);

        metric.reset();
        assertThat(metric.getOrCreateNotLabeled().getAsLong()).isEqualTo(10L);
    }

    @Test
    void testOperatorDefaultCustomInitialValueWithLabels() {
        LongGauge metric = emptyMetricBuilder()
                .withDynamicLabelNames("label")
                .withOperator((l, r) -> l + r + 3L, false)
                .withInitValue(10L)
                .build();

        metric.getOrCreateLabeled("label", "1").update(5L);
        metric.getOrCreateLabeled("label", "2").update(4L);
        assertThat(metric.getOrCreateLabeled("label", "1").getAsLong()).isEqualTo(18L);
        assertThat(metric.getOrCreateLabeled("label", "2").getAsLong()).isEqualTo(17L);

        metric.reset();
        assertThat(metric.getOrCreateLabeled("label", "1").getAsLong()).isEqualTo(10L);
        assertThat(metric.getOrCreateLabeled("label", "2").getAsLong()).isEqualTo(10L);
    }

    @Test
    void testDefaultInitialValueNoLabels() {
        LongGauge metric = emptyMetricBuilder().build();
        assertThat(metric.getOrCreateNotLabeled().getAsLong()).isEqualTo(0L);

        metric.getOrCreateNotLabeled().update(1L);
        assertThat(metric.getOrCreateNotLabeled().getAsLong()).isEqualTo(1L);

        metric.reset();
        assertThat(metric.getOrCreateNotLabeled().getAsLong()).isEqualTo(0L);
    }

    @Test
    void testDefaultInitialValueWithLabels() {
        LongGauge metric = emptyMetricBuilder().withDynamicLabelNames("label").build();
        assertThat(metric.getOrCreateLabeled("label", "1").getAsLong()).isEqualTo(0L);

        metric.getOrCreateLabeled("label", "1").update(1L);
        metric.getOrCreateLabeled("label", "2").update(2L);
        assertThat(metric.getOrCreateLabeled("label", "1").getAsLong()).isEqualTo(1L);
        assertThat(metric.getOrCreateLabeled("label", "2").getAsLong()).isEqualTo(2L);

        metric.reset();
        assertThat(metric.getOrCreateLabeled("label", "1").getAsLong()).isEqualTo(0L);
        assertThat(metric.getOrCreateLabeled("label", "2").getAsLong()).isEqualTo(0L);
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, -789L, 0L, 789L, Long.MAX_VALUE})
    void testCustomInitialValueNoLabels(long initialValue) {
        LongGauge metric = emptyMetricBuilder().withInitValue(initialValue).build();
        assertThat(metric.getOrCreateNotLabeled().getAsLong()).isEqualTo(initialValue);

        metric.getOrCreateNotLabeled().update(10000L);
        assertThat(metric.getOrCreateNotLabeled().getAsLong()).isNotEqualTo(initialValue);

        metric.reset();
        assertThat(metric.getOrCreateNotLabeled().getAsLong()).isEqualTo(initialValue);
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, -789L, 0L, 789L, Long.MAX_VALUE})
    void testCustomInitializerNoLabels(long initialValue) {
        LongGauge metric =
                emptyMetricBuilder().withDefaultInitializer(() -> initialValue).build();
        assertThat(metric.getOrCreateNotLabeled().getAsLong()).isEqualTo(initialValue);
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, -789L, 0L, 789L, Long.MAX_VALUE})
    void testCustomInitialValueWithLabels(long initialValue) {
        LongGauge metric = emptyMetricBuilder()
                .withDynamicLabelNames("label")
                .withInitValue(initialValue)
                .build();

        assertThat(metric.getOrCreateLabeled("label", "1").getAsLong()).isEqualTo(initialValue);

        metric.getOrCreateLabeled("label", "1").update(10000L);

        assertThat(metric.getOrCreateLabeled(StatUtils.asInitializer(initialValue), "label", "1")
                        .getAsLong())
                .as("initializer is not used when measurement already initialized")
                .isNotEqualTo(initialValue);
    }
}
