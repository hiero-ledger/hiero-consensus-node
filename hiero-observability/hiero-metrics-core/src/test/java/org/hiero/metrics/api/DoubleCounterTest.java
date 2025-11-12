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

public class DoubleCounterTest extends AbstractStatefulMetricBaseTest<DoubleCounter, DoubleCounter.Builder> {

    @Override
    protected MetricType metricType() {
        return MetricType.COUNTER;
    }

    @Override
    protected Class<DoubleCounter> metricClassType() {
        return DoubleCounter.class;
    }

    @Override
    protected DoubleCounter.Builder emptyMetricBuilder(String name) {
        return DoubleCounter.builder(name);
    }

    @Test
    void testNullNameMetricKey() {
        assertThatThrownBy(() -> DoubleCounter.key(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testNullMetricKeyBuilder() {
        assertThatThrownBy(() -> DoubleCounter.builder((MetricKey<DoubleCounter>) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("key must not be null");
    }

    @Test
    void testNullMetricNameBuilder() {
        assertThatThrownBy(() -> DoubleCounter.builder((String) null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testDefaultInitialValueNoLabels() {
        DoubleCounter metric = emptyMetricBuilder().build();
        assertThat(metric.getOrCreateNotLabeled().getAsDouble()).isEqualTo(0.0);

        metric.getOrCreateNotLabeled().increment();
        assertThat(metric.getOrCreateNotLabeled().getAsDouble()).isEqualTo(1.0);

        metric.reset();
        assertThat(metric.getOrCreateNotLabeled().getAsDouble()).isEqualTo(0.0);
    }

    @Test
    void testDefaultInitialValueWithLabels() {
        DoubleCounter metric =
                emptyMetricBuilder().withDynamicLabelNames("label").build();
        assertThat(metric.getOrCreateLabeled("label", "1").getAsDouble()).isEqualTo(0.0);

        metric.getOrCreateLabeled("label", "1").increment();
        metric.getOrCreateLabeled("label", "2").increment(2.5);
        assertThat(metric.getOrCreateLabeled("label", "1").getAsDouble()).isEqualTo(1.0);
        assertThat(metric.getOrCreateLabeled("label", "2").getAsDouble()).isEqualTo(2.5);

        metric.reset();
        assertThat(metric.getOrCreateLabeled("label", "1").getAsDouble()).isEqualTo(0.0);
        assertThat(metric.getOrCreateLabeled("label", "2").getAsDouble()).isEqualTo(0.0);
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.MIN_VALUE, Double.MAX_VALUE, 0.0, 0.123, 789.012, Double.POSITIVE_INFINITY})
    void testCustomInitialValueNoLabels(double initialValue) {
        DoubleCounter metric = emptyMetricBuilder().withInitValue(initialValue).build();
        assertThat(metric.getOrCreateNotLabeled().getAsDouble()).isEqualTo(initialValue);
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.MIN_VALUE, Double.MAX_VALUE, 0.0, 0.123, 789.012, Double.POSITIVE_INFINITY})
    void testCustomInitializerNoLabels(double initialValue) {
        DoubleCounter metric =
                emptyMetricBuilder().withDefaultInitializer(() -> initialValue).build();
        assertThat(metric.getOrCreateNotLabeled().getAsDouble()).isEqualTo(initialValue);
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.MIN_VALUE, 0.0, 0.123, 789.012})
    void testCustomInitialValueWithLabels(double initialValue) {
        DoubleCounter metric = emptyMetricBuilder()
                .withDynamicLabelNames("label")
                .withInitValue(initialValue)
                .build();

        assertThat(metric.getOrCreateLabeled("label", "1").getAsDouble()).isEqualTo(initialValue);

        metric.getOrCreateLabeled("label", "1").increment();
        metric.getOrCreateLabeled("label", "2").increment();

        assertThat(metric.getOrCreateLabeled(StatUtils.asInitializer(initialValue), "label", "1")
                        .getAsDouble())
                .as("initializer is not used when datapoint already initialized")
                .isNotEqualTo(initialValue);

        metric.reset();
        assertThat(metric.getOrCreateLabeled("label", "1").getAsDouble()).isEqualTo(initialValue);
        assertThat(metric.getOrCreateLabeled("label", "2").getAsDouble()).isEqualTo(initialValue);
    }
}
