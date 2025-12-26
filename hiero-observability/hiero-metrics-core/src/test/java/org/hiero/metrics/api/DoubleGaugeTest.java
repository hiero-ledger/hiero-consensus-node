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

public class DoubleGaugeTest extends AbstractSettableMetricBaseTest<DoubleGauge, DoubleGauge.Builder> {

    @Override
    protected MetricType metricType() {
        return MetricType.GAUGE;
    }

    @Override
    protected Class<DoubleGauge> metricClassType() {
        return DoubleGauge.class;
    }

    @Override
    protected DoubleGauge.Builder emptyMetricBuilder(String name) {
        return DoubleGauge.builder(name);
    }

    @Test
    void testNullNameMetricKey() {
        assertThatThrownBy(() -> DoubleGauge.key(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testNullMetricKeyBuilder() {
        assertThatThrownBy(() -> DoubleGauge.builder((MetricKey<DoubleGauge>) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("key must not be null");
    }

    @Test
    void testNullMetricNameBuilder() {
        assertThatThrownBy(() -> DoubleGauge.builder((String) null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testWithNullOperatorThrows() {
        DoubleGauge.Builder builder = emptyMetricBuilder();
        assertThatThrownBy(() -> builder.withOperator(null, false))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Operator must not be null");
    }

    @Test
    void testOperatorDefaultInitialValueNoLabels() {
        DoubleGauge metric =
                emptyMetricBuilder().withOperator((l, r) -> l + r + 1.5, false).build();

        metric.getOrCreateNotLabeled().update(2.0);
        assertThat(metric.getOrCreateNotLabeled().getAsDouble()).isEqualTo(3.5);

        metric.reset();
        assertThat(metric.getOrCreateNotLabeled().getAsDouble()).isEqualTo(0.0);
    }

    @Test
    void testOperatorDefaultInitialValueWithLabels() {
        DoubleGauge metric = emptyMetricBuilder()
                .withDynamicLabelNames("label")
                .withOperator((l, r) -> l + r + 1.5, false)
                .build();

        metric.getOrCreateLabeled("label", "1").update(2.0);
        metric.getOrCreateLabeled("label", "2").update(2.5);
        assertThat(metric.getOrCreateLabeled("label", "1").getAsDouble()).isEqualTo(3.5);
        assertThat(metric.getOrCreateLabeled("label", "2").getAsDouble()).isEqualTo(4.0);

        metric.reset();
        assertThat(metric.getOrCreateLabeled("label", "1").getAsDouble()).isEqualTo(0.0);
        assertThat(metric.getOrCreateLabeled("label", "2").getAsDouble()).isEqualTo(0.0);
    }

    @Test
    void testOperatorDefaultCustomInitialValueNoLabels() {
        DoubleGauge metric = emptyMetricBuilder()
                .withOperator((l, r) -> l + r + 1.5, false)
                .withInitValue(10.0)
                .build();

        metric.getOrCreateNotLabeled().update(2.0);
        assertThat(metric.getOrCreateNotLabeled().getAsDouble()).isEqualTo(13.5);

        metric.reset();
        assertThat(metric.getOrCreateNotLabeled().getAsDouble()).isEqualTo(10.0);
    }

    @Test
    void testOperatorDefaultCustomInitialValueWithLabels() {
        DoubleGauge metric = emptyMetricBuilder()
                .withDynamicLabelNames("label")
                .withOperator((l, r) -> l + r + 1.5, false)
                .withInitValue(10.0)
                .build();

        metric.getOrCreateLabeled("label", "1").update(2.0);
        metric.getOrCreateLabeled("label", "2").update(2.5);
        assertThat(metric.getOrCreateLabeled("label", "1").getAsDouble()).isEqualTo(13.5);
        assertThat(metric.getOrCreateLabeled("label", "2").getAsDouble()).isEqualTo(14.0);

        metric.reset();
        assertThat(metric.getOrCreateLabeled("label", "1").getAsDouble()).isEqualTo(10.0);
        assertThat(metric.getOrCreateLabeled("label", "2").getAsDouble()).isEqualTo(10.0);
    }

    @ParameterizedTest
    @ValueSource(
            doubles = {
                Double.MIN_VALUE,
                Double.MAX_VALUE,
                0.0,
                -789.012,
                789.012,
                Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY
            })
    void testCustomInitializerNoLabels(double initialValue) {
        DoubleGauge metric =
                emptyMetricBuilder().withDefaultInitializer(() -> initialValue).build();
        assertThat(metric.getOrCreateNotLabeled().getAsDouble()).isEqualTo(initialValue);
    }

    @Test
    void testNoOperatorDefaultInitialValueNoLabels() {
        DoubleGauge metric = emptyMetricBuilder().build();
        assertThat(metric.getOrCreateNotLabeled().getAsDouble()).isEqualTo(0.0);

        metric.getOrCreateNotLabeled().update(1.5);
        assertThat(metric.getOrCreateNotLabeled().getAsDouble()).isEqualTo(1.5);

        metric.reset();
        assertThat(metric.getOrCreateNotLabeled().getAsDouble()).isEqualTo(0.0);
    }

    @Test
    void testNoOperatorCustomInitialValueNoLabels() {
        DoubleGauge metric = emptyMetricBuilder().withInitValue(10.5).build();
        assertThat(metric.getOrCreateNotLabeled().getAsDouble()).isEqualTo(10.5);

        metric.getOrCreateNotLabeled().update(13.5);
        assertThat(metric.getOrCreateNotLabeled().getAsDouble()).isEqualTo(13.5);

        metric.reset();
        assertThat(metric.getOrCreateNotLabeled().getAsDouble()).isEqualTo(10.5);
    }

    @Test
    void testNoOperatorDefaultInitialValueWithLabels() {
        DoubleGauge metric = emptyMetricBuilder().withDynamicLabelNames("label").build();
        assertThat(metric.getOrCreateLabeled("label", "1").getAsDouble()).isEqualTo(0.0);

        metric.getOrCreateLabeled("label", "1").update(1.5);
        metric.getOrCreateLabeled("label", "2").update(2.0);
        assertThat(metric.getOrCreateLabeled("label", "1").getAsDouble()).isEqualTo(1.5);
        assertThat(metric.getOrCreateLabeled("label", "2").getAsDouble()).isEqualTo(2.0);

        metric.reset();
        assertThat(metric.getOrCreateLabeled("label", "1").getAsDouble()).isEqualTo(0.0);
        assertThat(metric.getOrCreateLabeled("label", "2").getAsDouble()).isEqualTo(0.0);
    }

    @ParameterizedTest
    @ValueSource(
            doubles = {
                Double.MIN_VALUE,
                Double.MAX_VALUE,
                0.0,
                -789.012,
                789.012,
                Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY
            })
    void testCustomInitialValueWithLabels(double initialValue) {
        DoubleGauge metric = emptyMetricBuilder()
                .withDynamicLabelNames("label")
                .withInitValue(initialValue)
                .build();

        assertThat(metric.getOrCreateLabeled("label", "1").getAsDouble()).isEqualTo(initialValue);

        metric.getOrCreateLabeled("label", "1").update(Double.NaN);

        assertThat(metric.getOrCreateLabeled(StatUtils.asInitializer(initialValue), "label", "1")
                        .getAsDouble())
                .as("initializer is not used when measurement already initialized")
                .isNotEqualTo(initialValue);
    }
}
