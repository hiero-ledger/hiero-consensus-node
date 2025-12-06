// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.hiero.metrics.api.core.Label;
import org.hiero.metrics.api.core.MetricType;
import org.hiero.metrics.api.stat.StatUtils;
import org.hiero.metrics.test.fixtures.StatContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

public class StatsGaugeAdapterTest
        extends AbstractStatefulMetricBaseTest<
                StatsGaugeAdapter<StatContainer>, StatsGaugeAdapter.Builder<StatContainer>> {

    @Override
    protected MetricType metricType() {
        return MetricType.GAUGE;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Class<StatsGaugeAdapter<StatContainer>> metricClassType() {
        return (Class<StatsGaugeAdapter<StatContainer>>) (Class<?>) StatsGaugeAdapter.class;
    }

    @Override
    protected StatsGaugeAdapter.Builder<StatContainer> emptyMetricBuilder(String name) {
        return emptyMetricBuilderWithInit(name, 0);
    }

    protected StatsGaugeAdapter.Builder<StatContainer> emptyMetricBuilderWithInit(String name, int init) {
        return StatsGaugeAdapter.builder(StatsGaugeAdapter.key(name), () -> new StatContainer(init))
                .withLongStat("cnt", StatContainer::getCounter)
                .withLongStat("sum", StatContainer::getSum)
                .withReset(StatContainer::reset);
    }

    @Test
    void testNullNameMetricKey() {
        assertThatThrownBy(() -> StatsGaugeAdapter.key(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testNullMetricKeyBuilder() {
        assertThatThrownBy(() -> StatsGaugeAdapter.builder(null, StatContainer::new))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("key must not be null");
    }

    @Test
    void testNullFactoryBuilderThrows() {
        assertThatThrownBy(() -> StatsGaugeAdapter.<StatContainer>builder(StatsGaugeAdapter.key("test"), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Default initializer must not be null");
    }

    @Test
    void testNoStatsDefinedThrows() {
        StatsGaugeAdapter.Builder<StatContainer> builder =
                StatsGaugeAdapter.builder(StatsGaugeAdapter.key(DEFAULT_NAME), StatContainer::new);

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("At least one stat must be defined");
    }

    @Test
    void testDuplicateStatNamesThrows() {
        StatsGaugeAdapter.Builder<StatContainer> builder = emptyMetricBuilder()
                .withLongStat("mystat", StatContainer::getCounter)
                .withDoubleStat("mystat", StatContainer::getAverage);

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Stat names must be unique");
    }

    @Test
    void testStatLabelConflictingConstantLabelThrows() {
        StatsGaugeAdapter.Builder<StatContainer> builder = emptyMetricBuilder()
                .withConstantLabel(new Label("mystat", "value"))
                .withStatLabel("mystat")
                .withLongStat("counter", StatContainer::getCounter);

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflicts with a constant label");
    }

    @Test
    void testDefaultStatLabelConflictingConstantLabelThrows() {
        StatsGaugeAdapter.Builder<StatContainer> builder = emptyMetricBuilder()
                .withConstantLabel(new Label(StatUtils.DEFAULT_STAT_LABEL, "value"))
                .withLongStat("counter", StatContainer::getCounter);

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflicts with a constant label");
    }

    @Test
    void testStatLabelConflictingDynamicLabelNameThrows() {
        StatsGaugeAdapter.Builder<StatContainer> builder = emptyMetricBuilder()
                .withDynamicLabelNames("mystat")
                .withStatLabel("mystat")
                .withLongStat("counter", StatContainer::getCounter);

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflicts with a dynamic label");
    }

    @Test
    void testDefaultStatLabelConflictingDynamicLabelNameThrows() {
        StatsGaugeAdapter.Builder<StatContainer> builder = emptyMetricBuilder()
                .withDynamicLabelNames(StatUtils.DEFAULT_STAT_LABEL)
                .withLongStat("counter", StatContainer::getCounter);

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflicts with a dynamic label");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void testWithBlankStatNameThrows(String statName) {
        StatsGaugeAdapter.Builder<StatContainer> builder = emptyMetricBuilder();

        assertThatThrownBy(() -> builder.withLongStat(statName, StatContainer::getCounter))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be blank");
    }

    @ParameterizedTest
    @MethodSource("org.hiero.metrics.TestUtils#invalidNames")
    void testWithInvalidStatLabelThrows(String statLabel) {
        StatsGaugeAdapter.Builder<StatContainer> builder = emptyMetricBuilder();

        assertThatThrownBy(() -> builder.withStatLabel(statLabel)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("org.hiero.metrics.TestUtils#validNames")
    void testWithValidStatLabel(String statLabel) {
        StatsGaugeAdapter.Builder<StatContainer> builder = emptyMetricBuilder().withStatLabel(statLabel);

        assertThat(builder.getStatLabel()).isEqualTo(statLabel);
    }

    @Test
    void testDefaultInitialValueNoLabels() {
        StatsGaugeAdapter<StatContainer> metric = emptyMetricBuilder().build();

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
        StatsGaugeAdapter<StatContainer> metric =
                emptyMetricBuilder().withDynamicLabelNames("label").build();

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
        StatsGaugeAdapter<StatContainer> metric =
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
        StatsGaugeAdapter<StatContainer> metric = emptyMetricBuilderWithInit(DEFAULT_NAME, initialValue)
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
