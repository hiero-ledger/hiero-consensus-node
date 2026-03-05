// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.hiero.metrics.TestUtils;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

public abstract class MetricBaseTest<M extends Metric, B extends Metric.Builder<B, M>> {

    protected static final String DEFAULT_NAME = "test_metric";

    protected abstract MetricType metricType();

    protected abstract B emptyMetricBuilder(String name);

    protected B emptyMetricBuilder() {
        return emptyMetricBuilder(DEFAULT_NAME);
    }

    protected void resetMetric(M metric) {
        metric.reset();
    }

    /**
     * Tests for the Metric.Builder class.
     */
    @Nested
    protected class BuilderTests {

        @Test
        void testWithNullEnumUnitThrows() {
            Unit nullUnit = null;
            B builder = emptyMetricBuilder();

            assertThatThrownBy(() -> builder.setUnit(nullUnit))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("unit must not be null");
        }

        @ParameterizedTest
        @MethodSource("org.hiero.metrics.TestUtils#invalidUnitNames")
        void testWithInvalidStringUnit(String invalidUnit) {
            if (invalidUnit == null || invalidUnit.isBlank()) {
                // already tested in other tests
                return;
            }

            B builder = emptyMetricBuilder();

            assertThatThrownBy(() -> builder.setUnit(invalidUnit)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void testDynamicLabelNamesNullArrayThrows() {
            B builder = emptyMetricBuilder();
            String[] nullLabelNames = null;

            assertThatThrownBy(() -> builder.addDynamicLabelNames(nullLabelNames))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("label names must not be null");
        }

        @Test
        void testNullDynamicLabelNameThrows() {
            B builder = emptyMetricBuilder();

            assertThatThrownBy(() -> builder.addDynamicLabelNames("label_name", null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void testDynamicLabelNameSameAsMetricNameThrows() {
            B builder = emptyMetricBuilder();

            assertThatThrownBy(() -> builder.addDynamicLabelNames("test_metric"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Label name must not be the same as metric name");
        }

        @ParameterizedTest
        @MethodSource("org.hiero.metrics.TestUtils#invalidLabelNames")
        void testInvalidDynamicLabelNameThrows(String invalidDynamicLabelName) {
            B builder = emptyMetricBuilder();

            assertThatThrownBy(() -> builder.addDynamicLabelNames(invalidDynamicLabelName))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void testNullStaticLabelsArrayThrows() {
            B builder = emptyMetricBuilder();
            Label[] nullArray = null;

            assertThatThrownBy(() -> builder.addStaticLabels(nullArray))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("labels must not be null");
        }

        @Test
        void testStaticLabelNameSameAsMetricNameThrows() {
            B builder = emptyMetricBuilder();

            assertThatThrownBy(() -> builder.addStaticLabels(new Label("test_metric", "value")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Label name must not be the same as metric name");
        }

        @Test
        void testDuplicateStaticLabelNamesThrows() {
            B builder = emptyMetricBuilder().addStaticLabels(new Label("label1", "value1"));

            assertThatThrownBy(() -> builder.addStaticLabels(new Label("label1", "value2")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("conflicts with existing");
        }
    }

    /**
     * Tests for the Metric class.
     */
    @Nested
    protected class BaseMetricTests {

        @Test
        void testEmptyMetricBuilderMetricProperties() {
            M metric = emptyMetricBuilder().build();

            // metadata
            assertThat(metric.name()).isEqualTo(DEFAULT_NAME);
            assertThat(metric.type()).isEqualTo(metricType());
            assertThat(metric.unit()).isNull();
            assertThat(metric.description()).isNull();

            // labels are empty by default
            assertThat(metric.staticLabels())
                    .as("Static labels are empty by default")
                    .isEmpty();
            assertThat(metric.dynamicLabelNames())
                    .as("Dynamic label names are empty by default")
                    .isEmpty();
        }

        @Test
        void testMetricImmutabilityAfterBuilderModification() {
            String description = "description";
            String unit = "unit";
            String staticLabelName = "static_label";
            String dynamicLabelName = "dynamic_label";

            B builder = emptyMetricBuilder()
                    .setDescription(description)
                    .setUnit(unit)
                    .addStaticLabels(new Label(staticLabelName, "value"))
                    .addDynamicLabelNames(dynamicLabelName);

            M metric = builder.build();

            // Modify builder after building the metric
            builder.setDescription(description + "_")
                    .setUnit(unit + "_")
                    .addStaticLabels(new Label(staticLabelName + "_", "value"))
                    .addDynamicLabelNames(dynamicLabelName + "_");

            // Metric metadata should remain unchanged
            assertThat(metric.description()).isEqualTo(description);
            assertThat(metric.unit()).isEqualTo(unit);
            assertThat(metric.staticLabels()).containsExactly(new Label(staticLabelName, "value"));
            assertThat(metric.dynamicLabelNames()).containsExactly(dynamicLabelName);
        }

        @Test
        void testSetNullDescription() {
            M metric = emptyMetricBuilder().setDescription(null).build();

            assertThat(metric.description()).isNull();
        }

        @Test
        void testSetEmptyDescription() {
            M metric = emptyMetricBuilder().setDescription("").build();

            assertThat(metric.description()).isNotNull();
            assertThat(metric.description()).isEmpty();
        }

        @ParameterizedTest
        @MethodSource("org.hiero.metrics.TestUtils#invalidMetricNames")
        void testSetDescriptionAsInvalidMetricName(String description) {
            M metric = emptyMetricBuilder().setDescription(description).build();

            assertThat(metric.description()).as("Description could be anything").isEqualTo(description);
        }

        @ParameterizedTest
        @MethodSource("org.hiero.metrics.TestUtils#validDescriptions")
        void testSetValidDescription(String description) {
            M metric = emptyMetricBuilder().setDescription(description).build();

            assertThat(metric.description()).as("Description could be anything").isEqualTo(description);
        }

        @Test
        void testDescriptionOverride() {
            M metric = emptyMetricBuilder()
                    .setDescription("first description")
                    .setDescription("second description")
                    .build();

            assertThat(metric.description()).isEqualTo("second description");
        }

        @Test
        void testSetNullStringUnit() {
            String nullUnit = null;
            M metric = emptyMetricBuilder().setUnit(nullUnit).build();

            assertThat(metric.unit()).isNull();
        }

        @Test
        void testSetEmptyStringUnit() {
            M metric = emptyMetricBuilder().setUnit("").build();

            assertThat(metric.unit()).isNull();
        }

        @ParameterizedTest
        @MethodSource("org.hiero.metrics.TestUtils#validUnitNames")
        void testSetValidStringUnit(String validUnit) {
            M metric = emptyMetricBuilder().setUnit(validUnit).build();

            assertThat(metric.unit()).isEqualTo(validUnit);
        }

        @Test
        void testUnitOverride() {
            M metric = emptyMetricBuilder()
                    .setUnit("custom_unit")
                    .setUnit(Unit.BYTE_UNIT)
                    .build();

            assertThat(metric.unit()).isEqualTo(Unit.BYTE_UNIT.toString());
        }

        @ParameterizedTest
        @EnumSource(Unit.class)
        void testSetValidEnumUnit(Unit unit) {
            M metric = emptyMetricBuilder().setUnit(unit).build();

            assertThat(metric.unit()).isEqualTo(unit.toString());
        }

        @Test
        void testRegisterInRegistry() {
            MetricRegistry registry = MetricRegistry.builder().build();
            B builder = emptyMetricBuilder();

            M registeredMetric = builder.register(registry);
            M fetchedMetric = registry.getMetric(builder.key());

            assertThat(fetchedMetric.name()).isEqualTo(DEFAULT_NAME);
            assertThat(fetchedMetric).isSameAs(registeredMetric);
        }

        @Test
        void testAddEmptyDynamicLabelNames() {
            M metric = emptyMetricBuilder().addDynamicLabelNames().build();

            assertThat(metric.dynamicLabelNames()).isNotNull();
            assertThat(metric.dynamicLabelNames()).isEmpty();
        }

        @Test
        void testDynamicLabelNamesSameInstance() {
            M metric = emptyMetricBuilder().addDynamicLabelNames("label1").build();

            assertThat(metric.dynamicLabelNames()).isSameAs(metric.dynamicLabelNames());
        }

        @ParameterizedTest
        @MethodSource("org.hiero.metrics.TestUtils#validLabelNames")
        void testAddSingleValidDynamicLabelName(String validLabelName) {
            M metric = emptyMetricBuilder().addDynamicLabelNames(validLabelName).build();

            assertThat(metric.dynamicLabelNames()).containsExactly(validLabelName);
        }

        @Test
        void testAddMultipleValidDynamicLabelNames() {
            M metric = emptyMetricBuilder()
                    .addDynamicLabelNames(TestUtils.validLabelNames())
                    .build();

            assertThat(metric.dynamicLabelNames()).containsExactlyInAnyOrder(TestUtils.validLabelNames());
        }

        @Test
        void testDynamicLabelNamesAreImmutable() {
            M metric = emptyMetricBuilder()
                    .addDynamicLabelNames("label1", "label2")
                    .build();

            List<String> dynamicLabels = metric.dynamicLabelNames();

            assertThatThrownBy(() -> dynamicLabels.add("label3")).isInstanceOf(UnsupportedOperationException.class);

            assertThatThrownBy(() -> dynamicLabels.remove("label1")).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void testDynamicLabelNamesOrder() {
            M metric = emptyMetricBuilder()
                    .addDynamicLabelNames("z_label", "a_label", "m_label")
                    .build();

            assertThat(metric.dynamicLabelNames()).containsExactly("a_label", "m_label", "z_label");
        }

        @Test
        void testDynamicLabelNamesDuplicates() {
            M metric = emptyMetricBuilder()
                    .addDynamicLabelNames("label1", "label2", "label1")
                    .addDynamicLabelNames("label2", "label3")
                    .build();

            assertThat(metric.dynamicLabelNames()).containsExactly("label1", "label2", "label3");
        }

        @Test
        void testAddEmptyStaticLabels() {
            M metric = emptyMetricBuilder().addStaticLabels().build();

            assertThat(metric.staticLabels()).isNotNull();
            assertThat(metric.staticLabels()).isEmpty();
        }

        @Test
        void testStaticLabelsSameInstance() {
            M metric = emptyMetricBuilder()
                    .addStaticLabels(new Label("label1", "value"))
                    .build();

            assertThat(metric.dynamicLabelNames()).isSameAs(metric.dynamicLabelNames());
        }

        @ParameterizedTest
        @MethodSource("org.hiero.metrics.TestUtils#validLabelNames")
        void testAddSingleValidStaticLabel(String validLabelName) {
            Label label = new Label(validLabelName, "value");
            M metric = emptyMetricBuilder().addStaticLabels(label).build();

            assertThat(metric.staticLabels()).containsExactly(label);
        }

        @Test
        void testAddMultipleValidStaticLabels() {
            Label[] labels = Stream.of(TestUtils.validLabelNames())
                    .map(l -> new Label(l, "value"))
                    .toArray(Label[]::new);

            M metric = emptyMetricBuilder().addStaticLabels(labels).build();

            assertThat(metric.staticLabels()).containsExactlyInAnyOrderElementsOf(Arrays.asList(labels));
        }

        @Test
        void testStaticLabelsAreImmutable() {
            M metric = emptyMetricBuilder()
                    .addStaticLabels(new Label("label1", "value"))
                    .build();

            List<Label> staticLabels = metric.staticLabels();

            assertThatThrownBy(() -> staticLabels.add(new Label("label2", "value")))
                    .isInstanceOf(UnsupportedOperationException.class);

            assertThatThrownBy(() -> staticLabels.remove(new Label("label1", "value")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void testStaticLabelsOrder() {
            M metric = emptyMetricBuilder()
                    .addStaticLabels(new Label("z_label", "value"))
                    .addStaticLabels(new Label("a_label", "value"))
                    .addStaticLabels(new Label("m_label", "value"))
                    .build();

            List<String> actual =
                    metric.staticLabels().stream().map(Label::name).toList();
            assertThat(actual).containsExactly("a_label", "m_label", "z_label");
        }

        @Test
        void testDuplicateStaticLabelAndDynamicLabelThrowsOnBuild() {
            B builder = emptyMetricBuilder()
                    .addStaticLabels(new Label("label1", "value1"))
                    .addDynamicLabelNames("label1");

            assertThatThrownBy(builder::build)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("conflicts with a static label");
        }

        @Test
        void testToString() {
            M metric = emptyMetricBuilder()
                    .setDescription("desc")
                    .setUnit(Unit.BYTE_UNIT)
                    .addStaticLabels(new Label("static_label", "static_value"))
                    .addDynamicLabelNames("dynamic_label")
                    .build();

            assertThat(metric.toString())
                    .contains(
                            metric.name(),
                            metric.type().toString(),
                            "desc",
                            Unit.BYTE_UNIT.toString(),
                            "static_label",
                            "static_value",
                            "dynamic_label");
        }
    }

    /**
     * Tests for the Metric.snapshot() method.
     */
    @Nested
    protected class SnapshotTests {

        @Test
        void testEmptySnapshotNoLabels() {
            M metric = emptyMetricBuilder().build();
            verifySnapshotIsEmpty(metric);
        }

        @Test
        void testEmptySnapshotWithLabels() {
            M metric = emptyMetricBuilder()
                    .addDynamicLabelNames("label1", "label2")
                    .build();
            verifySnapshotIsEmpty(metric);
        }

        protected void verifySnapshotIsEmpty(M metric) {
            assertThat(metric.snapshot()).isEmpty();
        }
    }
}
