// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.hiero.metrics.api.core.Label;
import org.hiero.metrics.api.core.MetricKey;
import org.hiero.metrics.api.core.MetricType;
import org.hiero.metrics.api.datapoint.StateSetDataPoint;
import org.hiero.metrics.api.utils.Unit;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

public class StateSetTest
        extends AbstractStatefulMetricBaseTest<
                StateSet<StateSetTest.TestEnum>, StateSet.Builder<StateSetTest.TestEnum>> {

    public enum TestEnum {
        A,
        B,
        C
    }

    @Override
    protected MetricType metricType() {
        return MetricType.STATE_SET;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Class<StateSet<TestEnum>> metricClassType() {
        return (Class<StateSet<TestEnum>>) (Class<?>) StateSet.class;
    }

    @Override
    protected StateSet.Builder<TestEnum> emptyMetricBuilder(String name) {
        return StateSet.builder(name, TestEnum.class);
    }

    /**
     *  Some tests should be disabled or overridden from the base class as they are not applicable to StateSet
     */
    @Nested
    class MetricTests extends AbstractMetricBaseTest.MetricTests {

        @ParameterizedTest
        @MethodSource("org.hiero.metrics.TestUtils#validUnitNames")
        @Override
        void testWithValidStringUnit(String validUnit) {
            StateSet<TestEnum> metric = emptyMetricBuilder().withUnit(validUnit).build();

            assertThat(metric.metadata().unit()).isEmpty();
        }

        @Test
        @Override
        void testUnitOverride() {
            StateSet<TestEnum> metric = emptyMetricBuilder()
                    .withUnit("custom_unit")
                    .withUnit(Unit.BYTE_UNIT)
                    .build();

            assertThat(metric.metadata().unit()).isEmpty();
        }

        @ParameterizedTest
        @EnumSource(Unit.class)
        @Override
        void testWithValidEnumUnit(Unit unit) {
            StateSet<TestEnum> metric = emptyMetricBuilder().withUnit(unit).build();

            assertThat(metric.metadata().unit()).isEmpty();
        }

        @Test
        @Override
        void testMetricImmutabilityAfterBuilderModification() {
            String description = "description";
            String unit = "unit";
            String staticLabelName = "static_label";
            String dynamicLabelName = "dynamic_label";

            StateSet.Builder<TestEnum> builder = emptyMetricBuilder()
                    .withDescription(description)
                    .withUnit(unit)
                    .withStaticLabel(new Label(staticLabelName, "value"))
                    .withDynamicLabelNames(dynamicLabelName);

            StateSet<TestEnum> metric = builder.build();

            // Modify builder after building the metric
            builder.withDescription(description + "_")
                    .withUnit(unit + "_")
                    .withStaticLabel(new Label(staticLabelName + "_", "value"))
                    .withDynamicLabelNames(dynamicLabelName + "_");

            // Metric metadata should remain unchanged
            assertThat(metric.metadata().description()).isEqualTo(description);
            assertThat(metric.metadata().unit()).isEmpty();
            assertThat(metric.staticLabels()).containsExactly(new Label(staticLabelName, "value"));
            assertThat(metric.dynamicLabelNames()).containsExactly(dynamicLabelName);
        }
    }

    @Test
    void testNullNameMetricKey() {
        assertThatThrownBy(() -> StateSet.key(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testNullMetricKeyBuilder() {
        assertThatThrownBy(() -> StateSet.builder((MetricKey<StateSet<TestEnum>>) null, TestEnum.class))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("key must not be null");
    }

    @Test
    void testNullMetricNameBuilder() {
        assertThatThrownBy(() -> StateSet.builder((String) null, TestEnum.class))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testDefaultInitialValueNoLabels() {
        StateSet<TestEnum> metric = emptyMetricBuilder().build();
        verifyStates(metric.getOrCreateNotLabeled(), false, false, false);

        metric.getOrCreateNotLabeled().setTrue(TestEnum.A);
        metric.getOrCreateNotLabeled().setTrue(TestEnum.C);
        verifyStates(metric.getOrCreateNotLabeled(), true, false, true);

        metric.reset();
        verifyStates(metric.getOrCreateNotLabeled(), false, false, false);
    }

    @Test
    void testDefaultInitialValueWithLabels() {
        StateSet<TestEnum> metric =
                emptyMetricBuilder().withDynamicLabelNames("label").build();

        verifyStates(metric.getOrCreateLabeled("label", "1"), false, false, false);

        metric.getOrCreateLabeled("label", "1").setTrue(TestEnum.A);
        metric.getOrCreateLabeled("label", "2").setTrue(TestEnum.C);
        verifyStates(metric.getOrCreateLabeled("label", "1"), true, false, false);
        verifyStates(metric.getOrCreateLabeled("label", "2"), false, false, true);

        metric.reset();
        verifyStates(metric.getOrCreateLabeled("label", "1"), false, false, false);
        verifyStates(metric.getOrCreateLabeled("label", "2"), false, false, false);
    }

    @Test
    void testCustomInitialValueNoLabels() {
        StateSet<TestEnum> metric =
                emptyMetricBuilder().withDefaultInitializer(Set.of(TestEnum.B)).build();
        verifyStates(metric.getOrCreateNotLabeled(), false, true, false);

        metric.getOrCreateNotLabeled().setTrue(TestEnum.C);
        metric.getOrCreateNotLabeled().setFalse(TestEnum.B);
        verifyStates(metric.getOrCreateNotLabeled(), false, false, true);

        metric.reset();
        verifyStates(metric.getOrCreateNotLabeled(), false, true, false);
    }

    @Test
    void testCustomInitialValueWithLabels() {
        StateSet<TestEnum> metric = emptyMetricBuilder()
                .withDynamicLabelNames("label")
                .withDefaultInitializer(Set.of(TestEnum.B))
                .build();

        verifyStates(metric.getOrCreateLabeled("label", "1"), false, true, false);

        metric.getOrCreateLabeled("label", "1").setFalse(TestEnum.B);
        metric.getOrCreateLabeled("label", "1").setTrue(TestEnum.C);
        metric.getOrCreateLabeled("label", "2").setTrue(TestEnum.A);

        // initializer should not interfere with already initialized datapoints
        verifyStates(metric.getOrCreateLabeled(Set.of(TestEnum.A), "label", "1"), false, false, true);
        verifyStates(metric.getOrCreateLabeled("label", "2"), true, true, false);

        metric.reset();
        verifyStates(metric.getOrCreateLabeled("label", "1"), false, true, false);
        verifyStates(metric.getOrCreateLabeled("label", "2"), false, true, false);
    }

    private void verifyStates(StateSetDataPoint<TestEnum> dataPoint, boolean... values) {
        assertThat(dataPoint.getState(TestEnum.A)).isEqualTo(values[0]);
        assertThat(dataPoint.getState(TestEnum.B)).isEqualTo(values[1]);
        assertThat(dataPoint.getState(TestEnum.C)).isEqualTo(values[2]);
    }
}
