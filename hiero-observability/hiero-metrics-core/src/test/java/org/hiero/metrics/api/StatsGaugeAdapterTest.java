// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.IntSupplier;
import org.hiero.metrics.api.core.MetricType;
import org.hiero.metrics.api.stat.StatUtils;
import org.hiero.metrics.test.fixtures.StatContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class StatsGaugeAdapterTest
        extends AbstractStatefulMetricBaseTest<
                StatsGaugeAdapter<IntSupplier, StatContainer>, StatsGaugeAdapter.Builder<IntSupplier, StatContainer>> {

    @Override
    protected MetricType metricType() {
        return MetricType.GAUGE;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Class<StatsGaugeAdapter<IntSupplier, StatContainer>> metricClassType() {
        return (Class<StatsGaugeAdapter<IntSupplier, StatContainer>>) (Class<?>) StatsGaugeAdapter.class;
    }

    @Override
    protected StatsGaugeAdapter.Builder<IntSupplier, StatContainer> emptyMetricBuilder(String name) {
        return emptyMetricBuilderWithInit(name, 0);
    }

    protected StatsGaugeAdapter.Builder<IntSupplier, StatContainer> emptyMetricBuilderWithInit(String name, int init) {
        return StatsGaugeAdapter.<IntSupplier, StatContainer>builder(
                        StatsGaugeAdapter.key(name), () -> init, StatContainer::new)
                .withLongStat("cnt", StatContainer::getCounter)
                .withLongStat("sum", StatContainer::getSum)
                .withReset(StatContainer::reset);
    }

    @Test
    void testDefaultInitialValueNoLabels() {
        StatsGaugeAdapter<IntSupplier, StatContainer> metric =
                emptyMetricBuilder().build();

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
        StatsGaugeAdapter<IntSupplier, StatContainer> metric =
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
        StatsGaugeAdapter<IntSupplier, StatContainer> metric =
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
        StatsGaugeAdapter<IntSupplier, StatContainer> metric = emptyMetricBuilderWithInit(DEFAULT_NAME, initialValue)
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
