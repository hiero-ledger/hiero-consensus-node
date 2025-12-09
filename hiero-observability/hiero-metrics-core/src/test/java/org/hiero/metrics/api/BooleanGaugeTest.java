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

public class BooleanGaugeTest extends AbstractSettableMetricBaseTest<BooleanGauge, BooleanGauge.Builder> {

    @Override
    protected MetricType metricType() {
        return MetricType.GAUGE;
    }

    @Override
    protected Class<BooleanGauge> metricClassType() {
        return BooleanGauge.class;
    }

    @Override
    protected BooleanGauge.Builder emptyMetricBuilder(String name) {
        return BooleanGauge.builder(name);
    }

    @Test
    void testNullNameMetricKey() {
        assertThatThrownBy(() -> BooleanGauge.key(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testNullMetricKeyBuilder() {
        assertThatThrownBy(() -> BooleanGauge.builder((MetricKey<BooleanGauge>) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("key must not be null");
    }

    @Test
    void testNullMetricNameBuilder() {
        assertThatThrownBy(() -> BooleanGauge.builder((String) null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testDefaultInitialValueNoLabels() {
        BooleanGauge metric = emptyMetricBuilder().build();
        assertThat(metric.getOrCreateNotLabeled().getAsBoolean()).isFalse();

        metric.getOrCreateNotLabeled().setTrue();
        assertThat(metric.getOrCreateNotLabeled().getAsBoolean()).isTrue();

        metric.reset();
        assertThat(metric.getOrCreateNotLabeled().getAsBoolean()).isFalse();
    }

    @Test
    void testDefaultInitialValueWithLabels() {
        BooleanGauge metric =
                emptyMetricBuilder().withDynamicLabelNames("label").build();
        assertThat(metric.getOrCreateLabeled("label", "1").getAsBoolean()).isFalse();

        metric.getOrCreateLabeled("label", "1").setTrue();
        metric.getOrCreateLabeled("label", "2").setTrue();
        assertThat(metric.getOrCreateLabeled("label", "1").getAsBoolean()).isTrue();
        assertThat(metric.getOrCreateLabeled("label", "2").getAsBoolean()).isTrue();

        metric.reset();
        assertThat(metric.getOrCreateLabeled("label", "1").getAsBoolean()).isFalse();
        assertThat(metric.getOrCreateLabeled("label", "2").getAsBoolean()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void testCustomInitialValueNoLabels(boolean initialValue) {
        BooleanGauge metric = emptyMetricBuilder().withInitValue(initialValue).build();
        assertThat(metric.getOrCreateNotLabeled().getAsBoolean()).isEqualTo(initialValue);

        metric.getOrCreateNotLabeled().set(!initialValue);
        assertThat(metric.getOrCreateNotLabeled().getAsBoolean()).isEqualTo(!initialValue);

        metric.reset();
        assertThat(metric.getOrCreateNotLabeled().getAsBoolean()).isEqualTo(initialValue);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void testCustomInitializerNoLabels(boolean initialValue) {
        BooleanGauge metric =
                emptyMetricBuilder().withDefaultInitializer(() -> initialValue).build();
        assertThat(metric.getOrCreateNotLabeled().getAsBoolean()).isEqualTo(initialValue);

        metric.getOrCreateNotLabeled().set(!initialValue);
        assertThat(metric.getOrCreateNotLabeled().getAsBoolean()).isEqualTo(!initialValue);

        metric.reset();
        assertThat(metric.getOrCreateNotLabeled().getAsBoolean()).isEqualTo(initialValue);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void testCustomInitialValueWithLabels(boolean initialValue) {
        BooleanGauge metric = emptyMetricBuilder()
                .withDynamicLabelNames("label")
                .withInitValue(initialValue)
                .build();

        assertThat(metric.getOrCreateLabeled("label", "1").getAsBoolean()).isEqualTo(initialValue);

        metric.getOrCreateLabeled("label", "1").set(!initialValue);

        assertThat(metric.getOrCreateLabeled(StatUtils.asInitializer(initialValue), "label", "1")
                        .getAsBoolean())
                .as("initializer is not used when measurement already initialized")
                .isEqualTo(!initialValue);

        metric.reset();
        assertThat(metric.getOrCreateLabeled("label", "1").getAsBoolean()).isEqualTo(initialValue);
    }
}
