// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.IntSupplier;
import org.hiero.metrics.api.core.MetricType;
import org.hiero.metrics.api.core.ToLongOrDoubleFunction;
import org.hiero.metrics.api.stat.StatUtils;
import org.hiero.metrics.test.fixtures.StatContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class GaugeAdapterTest
        extends AbstractStatefulMetricBaseTest<
                GaugeAdapter<IntSupplier, StatContainer>, GaugeAdapter.Builder<IntSupplier, StatContainer>> {

    @Override
    protected MetricType metricType() {
        return MetricType.GAUGE;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Class<GaugeAdapter<IntSupplier, StatContainer>> metricClassType() {
        return (Class<GaugeAdapter<IntSupplier, StatContainer>>) (Class<?>) GaugeAdapter.class;
    }

    @Override
    protected GaugeAdapter.Builder<IntSupplier, StatContainer> emptyMetricBuilder(String name) {
        return emptyMetricBuilderWithInit(name, 0);
    }

    protected GaugeAdapter.Builder<IntSupplier, StatContainer> emptyMetricBuilderWithInit(String name, int init) {
        return GaugeAdapter.<IntSupplier, StatContainer>builder(
                        name, () -> init, StatContainer::new, new ToLongOrDoubleFunction<>(StatContainer::getCounter))
                .withReset(StatContainer::reset);
    }

    @Test
    void testDefaultInitialValueNoLabels() {
        GaugeAdapter<IntSupplier, StatContainer> metric = emptyMetricBuilder().build();

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
        GaugeAdapter<IntSupplier, StatContainer> metric =
                emptyMetricBuilder().withDynamicLabelNames("label").build();

        assertThat(metric.getOrCreateLabeled("label", "1").getCounter()).isEqualTo(0);
        assertThat(metric.getOrCreateLabeled("label", "1").getSum()).isEqualTo(0);

        metric.getOrCreateLabeled("label", "1").update(2);
        metric.getOrCreateLabeled("label", "2").update(10);
        assertThat(metric.getOrCreateLabeled("label", "1").getCounter()).isEqualTo(1);
        assertThat(metric.getOrCreateLabeled("label", "1").getSum()).isEqualTo(2);
        assertThat(metric.getOrCreateLabeled("label", "2").getCounter()).isEqualTo(1);
        assertThat(metric.getOrCreateLabeled("label", "2").getSum()).isEqualTo(10);

        metric.reset();
        assertThat(metric.getOrCreateLabeled("label", "1").getCounter()).isEqualTo(0);
        assertThat(metric.getOrCreateLabeled("label", "1").getSum()).isEqualTo(0);
        assertThat(metric.getOrCreateLabeled("label", "2").getCounter()).isEqualTo(0);
        assertThat(metric.getOrCreateLabeled("label", "2").getSum()).isEqualTo(0);
    }

    @ParameterizedTest
    @ValueSource(ints = {-100, -1, 0, 1, 100})
    void testCustomInitialValueNoLabels(int initialValue) {
        GaugeAdapter<IntSupplier, StatContainer> metric =
                emptyMetricBuilderWithInit(DEFAULT_NAME, initialValue).build();
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
        GaugeAdapter<IntSupplier, StatContainer> metric = emptyMetricBuilderWithInit(DEFAULT_NAME, initialValue)
                .withDynamicLabelNames("label")
                .build();

        assertThat(metric.getOrCreateLabeled("label", "1").getCounter()).isEqualTo(initialValue);
        assertThat(metric.getOrCreateLabeled("label", "1").getSum()).isEqualTo(initialValue);

        metric.getOrCreateLabeled("label", "1").update(5);
        metric.getOrCreateLabeled("label", "2").update(10);
        assertThat(metric.getOrCreateLabeled(StatUtils.asInitializer(initialValue), "label", "1")
                        .getCounter())
                .isEqualTo(initialValue + 1);
        assertThat(metric.getOrCreateLabeled("label", "1").getSum()).isEqualTo(initialValue + 5);
        assertThat(metric.getOrCreateLabeled(StatUtils.asInitializer(initialValue), "label", "2")
                        .getCounter())
                .isEqualTo(initialValue + 1);
        assertThat(metric.getOrCreateLabeled("label", "2").getSum()).isEqualTo(initialValue + 10);

        metric.reset();
        assertThat(metric.getOrCreateLabeled("label", "1").getCounter()).isEqualTo(initialValue);
        assertThat(metric.getOrCreateLabeled("label", "1").getSum()).isEqualTo(initialValue);
        assertThat(metric.getOrCreateLabeled("label", "2").getCounter()).isEqualTo(initialValue);
        assertThat(metric.getOrCreateLabeled("label", "2").getSum()).isEqualTo(initialValue);
    }
}
