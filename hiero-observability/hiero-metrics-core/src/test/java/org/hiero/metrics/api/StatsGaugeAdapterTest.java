// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.IntSupplier;
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
    void testNullNameMetricKey() {
        assertThatThrownBy(() -> StatsGaugeAdapter.key(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testNullMetricKeyBuilder() {
        assertThatThrownBy(
                        () -> StatsGaugeAdapter.<IntSupplier, StatContainer>builder(null, () -> 0, StatContainer::new))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("key must not be null");
    }

    @Test
    void testNullInitializerBuilder() {
        assertThatThrownBy(() -> StatsGaugeAdapter.<IntSupplier, StatContainer>builder(
                        StatsGaugeAdapter.key("test"), null, StatContainer::new))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Default initializer must not be null");
    }

    @Test
    void testNullFactoryBuilder() {
        assertThatThrownBy(() -> StatsGaugeAdapter.<IntSupplier, StatContainer>builder(
                        StatsGaugeAdapter.key("test"), () -> 0, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Data point factory must not be null");
    }

    @Test
    void testNoStatsDefinedThrows() {
        StatsGaugeAdapter.Builder<IntSupplier, StatContainer> builder =
                StatsGaugeAdapter.builder(StatsGaugeAdapter.key(DEFAULT_NAME), () -> 0, StatContainer::new);

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("At least one stat must be defined");
    }

    @Test
    void testDuplicateStatNamesThrows() {
        StatsGaugeAdapter.Builder<IntSupplier, StatContainer> builder = emptyMetricBuilder()
                .withLongStat("mystat", StatContainer::getCounter)
                .withDoubleStat("mystat", StatContainer::getAverage);

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Stat names must be unique");
    }

    @Test
    void testStatLabelConflictingConstantLabelThrows() {
        StatsGaugeAdapter.Builder<IntSupplier, StatContainer> builder = emptyMetricBuilder()
                .withConstantLabel(new Label("mystat", "value"))
                .withStatLabel("mystat")
                .withLongStat("counter", StatContainer::getCounter);

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflicts with a constant label");
    }

    @Test
    void testDefaultStatLabelConflictingConstantLabelThrows() {
        StatsGaugeAdapter.Builder<IntSupplier, StatContainer> builder = emptyMetricBuilder()
                .withConstantLabel(new Label(StatUtils.DEFAULT_STAT_LABEL, "value"))
                .withLongStat("counter", StatContainer::getCounter);

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflicts with a constant label");
    }

    @Test
    void testStatLabelConflictingDynamicLabelNameThrows() {
        StatsGaugeAdapter.Builder<IntSupplier, StatContainer> builder = emptyMetricBuilder()
                .withDynamicLabelNames("mystat")
                .withStatLabel("mystat")
                .withLongStat("counter", StatContainer::getCounter);

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflicts with a dynamic label");
    }

    @Test
    void testDefaultStatLabelConflictingDynamicLabelNameThrows() {
        StatsGaugeAdapter.Builder<IntSupplier, StatContainer> builder = emptyMetricBuilder()
                .withDynamicLabelNames(StatUtils.DEFAULT_STAT_LABEL)
                .withLongStat("counter", StatContainer::getCounter);

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflicts with a dynamic label");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void testWithBlankStatNameThrows(String statName) {
        StatsGaugeAdapter.Builder<IntSupplier, StatContainer> builder = emptyMetricBuilder();

        assertThatThrownBy(() -> builder.withLongStat(statName, StatContainer::getCounter))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be blank");
    }

    @ParameterizedTest
    @MethodSource("org.hiero.metrics.TestUtils#invalidNames")
    void testWithInvalidStatLabelThrows(String statLabel) {
        StatsGaugeAdapter.Builder<IntSupplier, StatContainer> builder = emptyMetricBuilder();

        assertThatThrownBy(() -> builder.withStatLabel(statLabel)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("org.hiero.metrics.TestUtils#validNames")
    void testWithValidStatLabel(String statLabel) {
        StatsGaugeAdapter.Builder<IntSupplier, StatContainer> builder =
                emptyMetricBuilder().withStatLabel(statLabel);

        assertThat(builder.getStatLabel()).isEqualTo(statLabel);
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
