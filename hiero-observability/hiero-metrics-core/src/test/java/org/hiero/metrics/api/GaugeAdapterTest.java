// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.hiero.metrics.api.core.MetricKey;
import org.hiero.metrics.api.core.MetricType;
import org.hiero.metrics.api.core.ToNumberFunction;
import org.hiero.metrics.test.fixtures.StatContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class GaugeAdapterTest
        extends AbstractStatefulMetricBaseTest<GaugeAdapter<StatContainer>, GaugeAdapter.Builder<StatContainer>> {

    @Override
    protected MetricType metricType() {
        return MetricType.GAUGE;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Class<GaugeAdapter<StatContainer>> metricClassType() {
        return (Class<GaugeAdapter<StatContainer>>) (Class<?>) GaugeAdapter.class;
    }

    @Override
    protected GaugeAdapter.Builder<StatContainer> emptyMetricBuilder(String name) {
        return GaugeAdapter.builder(name, StatContainer::new, new ToNumberFunction<>(StatContainer::getCounter))
                .withReset(StatContainer::reset);
    }

    protected GaugeAdapter.Builder<StatContainer> emptyMetricBuilder(String name, int customInitialValue) {
        return GaugeAdapter.builder(
                        name,
                        () -> new StatContainer(customInitialValue),
                        new ToNumberFunction<>(StatContainer::getCounter))
                .withReset(StatContainer::reset);
    }

    protected GaugeAdapter.Builder<StatContainer> emptyDoubleMetricBuilder(String name) {
        return GaugeAdapter.builder(name, StatContainer::new, new ToNumberFunction<>(StatContainer::getAverage))
                .withReset(StatContainer::reset);
    }

    @Test
    void testNullNameMetricKey() {
        assertThatThrownBy(() -> GaugeAdapter.key(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testNullMetricKeyBuilder() {
        MetricKey<GaugeAdapter<StatContainer>> metricKey = null;

        assertThatThrownBy(() -> GaugeAdapter.builder(
                        metricKey, StatContainer::new, new ToNumberFunction<>(StatContainer::getCounter)))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("key must not be null");
    }

    @Test
    void testNullFactoryThrows() {
        assertThatThrownBy(() ->
                        GaugeAdapter.builder(DEFAULT_NAME, null, new ToNumberFunction<>(StatContainer::getCounter)))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Default initializer must not be null");
    }

    @Test
    void testNullExportGetterThrows() {
        assertThatThrownBy(() -> GaugeAdapter.builder(DEFAULT_NAME, StatContainer::new, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("exportGetter cannot be null");
    }

    @Test
    void testDefaultInitialValueNoLabels() {
        GaugeAdapter<StatContainer> metric = emptyMetricBuilder().build();

        assertThat(metric.getOrCreateNotLabeled().getCounter()).isEqualTo(0);
        assertThat(metric.getOrCreateNotLabeled().getSum()).isEqualTo(0);

        metric.getOrCreateNotLabeled().update(1);
        assertThat(metric.getOrCreateNotLabeled().getCounter()).isEqualTo(1);
        assertThat(metric.getOrCreateNotLabeled().getSum()).isEqualTo(1);

        metric.reset();
        assertThat(metric.getOrCreateNotLabeled().getCounter()).isEqualTo(0);
        assertThat(metric.getOrCreateNotLabeled().getSum()).isEqualTo(0);
    }

    @Test
    void testDefaultInitialValueWithLabels() {
        GaugeAdapter<StatContainer> metric = emptyDoubleMetricBuilder(DEFAULT_NAME)
                .withDynamicLabelNames("label")
                .build();

        assertThat(metric.getOrCreateLabeled("label", "1").getCounter()).isEqualTo(0);
        assertThat(metric.getOrCreateLabeled("label", "1").getSum()).isEqualTo(0);

        metric.getOrCreateLabeled("label", "1").update(2);
        metric.getOrCreateLabeled("label", "2").update(10);
        assertThat(metric.getOrCreateLabeled("label", "1").getCounter()).isEqualTo(1);
        assertThat(metric.getOrCreateLabeled("label", "1").getSum()).isEqualTo(2);
        assertThat(metric.getOrCreateLabeled("label", "2").getCounter()).isEqualTo(1);
        assertThat(metric.getOrCreateLabeled("label", "2").getSum()).isEqualTo(10);

        metric.getOrCreateLabeled(() -> new StatContainer(10), "label", "3").update(5);
        // initializer is ignored after first creation
        metric.getOrCreateLabeled(() -> new StatContainer(100), "label", "3").update(4);
        assertThat(metric.getOrCreateLabeled("label", "3").getCounter()).isEqualTo(12);
        assertThat(metric.getOrCreateLabeled("label", "3").getSum()).isEqualTo(19);

        metric.reset();
        assertThat(metric.getOrCreateLabeled("label", "1").getCounter()).isEqualTo(0);
        assertThat(metric.getOrCreateLabeled("label", "1").getSum()).isEqualTo(0);
        assertThat(metric.getOrCreateLabeled("label", "2").getCounter()).isEqualTo(0);
        assertThat(metric.getOrCreateLabeled("label", "2").getSum()).isEqualTo(0);
        assertThat(metric.getOrCreateLabeled("label", "3").getCounter()).isEqualTo(10);
        assertThat(metric.getOrCreateLabeled("label", "3").getSum()).isEqualTo(10);
    }

    @ParameterizedTest
    @ValueSource(ints = {-100, -1, 0, 1, 100})
    void testCustomInitialValueNoLabels(int initialValue) {
        GaugeAdapter<StatContainer> metric =
                emptyMetricBuilder(DEFAULT_NAME, initialValue).build();
        assertThat(metric.getOrCreateNotLabeled().getCounter()).isEqualTo(initialValue);
        assertThat(metric.getOrCreateNotLabeled().getSum()).isEqualTo(initialValue);

        metric.getOrCreateNotLabeled().update(10);
        assertThat(metric.getOrCreateNotLabeled().getCounter()).isEqualTo(initialValue + 1);
        assertThat(metric.getOrCreateNotLabeled().getSum()).isEqualTo(initialValue + 10);

        metric.reset();
        assertThat(metric.getOrCreateNotLabeled().getCounter()).isEqualTo(initialValue);
        assertThat(metric.getOrCreateNotLabeled().getSum()).isEqualTo(initialValue);
    }

    @ParameterizedTest
    @ValueSource(ints = {-100, -1, 0, 1, 100})
    void testCustomInitialValueWithLabels(int initialValue) {
        GaugeAdapter<StatContainer> metric = emptyMetricBuilder(DEFAULT_NAME, initialValue)
                .withDynamicLabelNames("label")
                .build();

        assertThat(metric.getOrCreateLabeled(() -> new StatContainer(10000), "label", "0")
                        .getCounter())
                .isEqualTo(10000);

        assertThat(metric.getOrCreateLabeled("label", "1").getCounter()).isEqualTo(initialValue);
        assertThat(metric.getOrCreateLabeled("label", "1").getSum()).isEqualTo(initialValue);

        metric.getOrCreateLabeled("label", "1").update(5);
        metric.getOrCreateLabeled("label", "2").update(10);
        assertThat(metric.getOrCreateLabeled(() -> new StatContainer(initialValue), "label", "1")
                        .getCounter())
                .isEqualTo(initialValue + 1); // initializer is ignored after first creation
        assertThat(metric.getOrCreateLabeled("label", "1").getSum()).isEqualTo(initialValue + 5);
        assertThat(metric.getOrCreateLabeled(() -> new StatContainer(initialValue), "label", "2")
                        .getCounter())
                .isEqualTo(initialValue + 1); // initializer is ignored after first creation
        assertThat(metric.getOrCreateLabeled("label", "2").getSum()).isEqualTo(initialValue + 10);

        metric.reset();
        assertThat(metric.getOrCreateLabeled("label", "0").getCounter()).isEqualTo(10000);
        assertThat(metric.getOrCreateLabeled("label", "1").getCounter()).isEqualTo(initialValue);
        assertThat(metric.getOrCreateLabeled("label", "1").getSum()).isEqualTo(initialValue);
        assertThat(metric.getOrCreateLabeled("label", "2").getCounter()).isEqualTo(initialValue);
        assertThat(metric.getOrCreateLabeled("label", "2").getSum()).isEqualTo(initialValue);
    }
}
