// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;
import org.hiero.metrics.TestUtils;
import org.hiero.metrics.api.core.Label;
import org.hiero.metrics.api.core.Metric;
import org.hiero.metrics.api.core.MetricRegistry;
import org.hiero.metrics.api.core.MetricType;
import org.hiero.metrics.api.utils.Unit;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

abstract class AbstractMetricBaseTest<M extends Metric, B extends Metric.Builder<B, M>> {

    protected static final String DEFAULT_NAME = "test_metric";

    protected abstract MetricType metricType();

    protected abstract Class<M> metricClassType();

    protected abstract B emptyMetricBuilder(String name);

    protected B emptyMetricBuilder() {
        return emptyMetricBuilder(DEFAULT_NAME);
    }

    @Nested
    class BuilderTests {

        @Test
        void testEmptyMetricBuilderProperties() {
            B builder = emptyMetricBuilder();

            // non-null properties
            assertThat(builder.type()).isEqualTo(metricType());
            assertThat(builder.key()).isNotNull();
            assertThat(builder.key().name()).isEqualTo(DEFAULT_NAME);
            assertThat(builder.key().type()).isEqualTo(metricClassType());

            // null properties
            assertThat(builder.getDescription())
                    .as("Description is null by default")
                    .isNull();
            assertThat(builder.getUnit()).as("Unit is null by default").isNull();

            // labels are empty by default
            assertThat(builder.getStaticLabels())
                    .as("Static labels are empty by default")
                    .isEmpty();
            assertThat(builder.getDynamicLabelNames())
                    .as("Dynamic label names are empty by default")
                    .isEmpty();
        }

        @Test
        void testWithNullEnumUnitThrows() {
            Unit nullUnit = null;
            B builder = emptyMetricBuilder();

            assertThatThrownBy(() -> builder.withUnit(nullUnit))
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

            assertThatThrownBy(() -> builder.withUnit(invalidUnit)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void testDynamicLabelsNullArrayThrows() {
            B builder = emptyMetricBuilder();
            String[] nullLabelNames = null;

            assertThatThrownBy(() -> builder.withDynamicLabelNames(nullLabelNames))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("label names must not be null");
        }

        @Test
        void testNullDynamicLabelThrows() {
            B builder = emptyMetricBuilder();

            assertThatThrownBy(() -> builder.withDynamicLabelNames("label_name", null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void testDynamicLabelSameAsMetricNameThrows() {
            B builder = emptyMetricBuilder();

            assertThatThrownBy(() -> builder.withDynamicLabelNames("test_metric"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Label name must not be the same as metric name");
        }

        @ParameterizedTest
        @MethodSource("org.hiero.metrics.TestUtils#invalidLabelNames")
        void testInvalidDynamicLabelNamesThrows(String invalidDynamicLabelName) {
            B builder = emptyMetricBuilder();

            assertThatThrownBy(() -> builder.withDynamicLabelNames(invalidDynamicLabelName))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void testNullStaticLabelsArrayThrows() {
            B builder = emptyMetricBuilder();
            Label[] nullArray = null;

            assertThatThrownBy(() -> builder.withStaticLabels(nullArray))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("labels must not be null");
        }

        @Test
        void testNullStaticLabelsCollectionThrows() {
            B builder = emptyMetricBuilder();
            Collection<Label> nullCollection = null;

            assertThatThrownBy(() -> builder.withStaticLabels(nullCollection))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("labels must not be null");
        }

        @Test
        void testStaticLabelSameAsMetricNameThrows() {
            B builder = emptyMetricBuilder();

            assertThatThrownBy(() -> builder.withStaticLabel(new Label("test_metric", "value")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Label name must not be the same as metric name");
        }

        @Test
        void testDuplicateStaticLabelNamesThrows() {
            B builder = emptyMetricBuilder().withStaticLabel(new Label("label1", "value1"));

            assertThatThrownBy(() -> builder.withStaticLabel(new Label("label1", "value2")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("conflicts with existing");
        }
    }

    @Nested
    class MetricTests {

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
                    .withDescription(description)
                    .withUnit(unit)
                    .withStaticLabel(new Label(staticLabelName, "value"))
                    .withDynamicLabelNames(dynamicLabelName);

            M metric = builder.build();

            // Modify builder after building the metric
            builder.withDescription(description + "_")
                    .withUnit(unit + "_")
                    .withStaticLabel(new Label(staticLabelName + "_", "value"))
                    .withDynamicLabelNames(dynamicLabelName + "_");

            // Metric metadata should remain unchanged
            assertThat(metric.description()).isEqualTo(description);
            assertThat(metric.unit()).isEqualTo(unit);
            assertThat(metric.staticLabels()).containsExactly(new Label(staticLabelName, "value"));
            assertThat(metric.dynamicLabelNames()).containsExactly(dynamicLabelName);
        }

        @Test
        void testWithNullDescription() {
            M metric = emptyMetricBuilder().withDescription(null).build();

            assertThat(metric.description()).isNull();
        }

        @Test
        void testWithEmptyDescription() {
            M metric = emptyMetricBuilder().withDescription("").build();

            assertThat(metric.description()).isNotNull();
            assertThat(metric.description()).isEmpty();
        }

        @ParameterizedTest
        @MethodSource("org.hiero.metrics.TestUtils#invalidMetricNames")
        void testWithDescriptionAsInvalidMetricName(String description) {
            M metric = emptyMetricBuilder().withDescription(description).build();

            assertThat(metric.description()).as("Description could be anything").isEqualTo(description);
        }

        @ParameterizedTest
        @MethodSource("org.hiero.metrics.TestUtils#validDescriptions")
        void testWithValidDescription(String description) {
            M metric = emptyMetricBuilder().withDescription(description).build();

            assertThat(metric.description()).as("Description could be anything").isEqualTo(description);
        }

        @Test
        void testDescriptionOverride() {
            M metric = emptyMetricBuilder()
                    .withDescription("first description")
                    .withDescription("second description")
                    .build();

            assertThat(metric.description()).isEqualTo("second description");
        }

        @Test
        void testWithNullStringUnit() {
            String nullUnit = null;
            M metric = emptyMetricBuilder().withUnit(nullUnit).build();

            assertThat(metric.unit()).isNull();
        }

        @Test
        void testWithEmptyStringUnit() {
            M metric = emptyMetricBuilder().withUnit("").build();

            assertThat(metric.unit()).isNull();
        }

        @ParameterizedTest
        @MethodSource("org.hiero.metrics.TestUtils#validUnitNames")
        void testWithValidStringUnit(String validUnit) {
            M metric = emptyMetricBuilder().withUnit(validUnit).build();

            assertThat(metric.unit()).isEqualTo(validUnit);
        }

        @Test
        void testUnitOverride() {
            M metric = emptyMetricBuilder()
                    .withUnit("custom_unit")
                    .withUnit(Unit.BYTE_UNIT)
                    .build();

            assertThat(metric.unit()).isEqualTo(Unit.BYTE_UNIT.toString());
        }

        @ParameterizedTest
        @EnumSource(Unit.class)
        void testWithValidEnumUnit(Unit unit) {
            M metric = emptyMetricBuilder().withUnit(unit).build();

            assertThat(metric.unit()).isEqualTo(unit.toString());
        }

        @Test
        void testRegister() {
            MetricRegistry registry = MetricRegistry.builder("registry").build();
            B builder = emptyMetricBuilder();

            builder.register(registry);

            M metric = registry.getMetric(builder.key());
            assertThat(metric.name()).isEqualTo("test_metric");
        }

        @Test
        void testEmptyDynamicLabels() {
            M metric = emptyMetricBuilder().withDynamicLabelNames().build();

            assertThat(metric.dynamicLabelNames()).isNotNull();
            assertThat(metric.dynamicLabelNames()).isEmpty();
        }

        @Test
        void testDynamicLabelsSameInstance() {
            M metric = emptyMetricBuilder().withDynamicLabelNames("label1").build();

            assertThat(metric.dynamicLabelNames()).isSameAs(metric.dynamicLabelNames());
        }

        @ParameterizedTest
        @MethodSource("org.hiero.metrics.TestUtils#validLabelNames")
        void testSingleValidDynamicLabelName(String validLabelName) {
            M metric =
                    emptyMetricBuilder().withDynamicLabelNames(validLabelName).build();

            assertThat(metric.dynamicLabelNames()).containsExactly(validLabelName);
        }

        @Test
        void testMultipleValidDynamicLabelName() {
            M metric = emptyMetricBuilder()
                    .withDynamicLabelNames(TestUtils.validLabelNames())
                    .build();

            assertThat(metric.dynamicLabelNames()).containsExactlyInAnyOrder(TestUtils.validLabelNames());
        }

        @Test
        void testDynamicLabelsImmutable() {
            M metric = emptyMetricBuilder()
                    .withDynamicLabelNames("label1", "label2")
                    .build();

            List<String> dynamicLabels = metric.dynamicLabelNames();

            assertThatThrownBy(() -> dynamicLabels.add("label3")).isInstanceOf(UnsupportedOperationException.class);

            assertThatThrownBy(() -> dynamicLabels.remove("label1")).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void testDynamicLabelsOrder() {
            M metric = emptyMetricBuilder()
                    .withDynamicLabelNames("z_label", "a_label", "m_label")
                    .build();

            assertThat(metric.dynamicLabelNames()).containsExactly("a_label", "m_label", "z_label");
        }

        @Test
        void testDynamicLabelNamesDuplicates() {
            M metric = emptyMetricBuilder()
                    .withDynamicLabelNames("label1", "label2", "label1")
                    .withDynamicLabelNames("label2", "label3")
                    .build();

            assertThat(metric.dynamicLabelNames()).containsExactly("label1", "label2", "label3");
        }

        @Test
        void testEmptyStaticLabelsArray() {
            M metric = emptyMetricBuilder().withStaticLabels().build();

            assertThat(metric.staticLabels()).isNotNull();
            assertThat(metric.staticLabels()).isEmpty();
        }

        @Test
        void testEmptyStaticLabelsCollection() {
            M metric = emptyMetricBuilder().withStaticLabels(new ArrayList<>()).build();

            assertThat(metric.staticLabels()).isNotNull();
            assertThat(metric.staticLabels()).isEmpty();
        }

        @Test
        void testStaticLabelsSameInstance() {
            M metric = emptyMetricBuilder()
                    .withStaticLabel(new Label("label1", "value"))
                    .build();

            assertThat(metric.dynamicLabelNames()).isSameAs(metric.dynamicLabelNames());
        }

        @ParameterizedTest
        @MethodSource("org.hiero.metrics.TestUtils#validLabelNames")
        void testSingleValidStaticLabelName(String validLabelName) {
            Label label = new Label(validLabelName, "value");
            M metric = emptyMetricBuilder().withStaticLabel(label).build();

            assertThat(metric.staticLabels()).containsExactly(label);
        }

        @Test
        void testMultipleValidStaticLabels() {
            List<Label> labels = Stream.of(TestUtils.validLabelNames())
                    .map(l -> new Label(l, "value"))
                    .toList();

            M metric = emptyMetricBuilder().withStaticLabels(labels).build();

            assertThat(metric.staticLabels()).containsExactlyInAnyOrderElementsOf(labels);
        }

        @Test
        void testStaticLabelsImmutable() {
            M metric = emptyMetricBuilder()
                    .withStaticLabel(new Label("label1", "value"))
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
                    .withStaticLabel(new Label("z_label", "value"))
                    .withStaticLabel(new Label("a_label", "value"))
                    .withStaticLabel(new Label("m_label", "value"))
                    .build();

            List<String> actual =
                    metric.staticLabels().stream().map(Label::name).toList();
            assertThat(actual).containsExactly("a_label", "m_label", "z_label");
        }

        @Test
        void testDuplicateStaticLabelAndDynamicLabelThrowsOnBuild() {
            B builder = emptyMetricBuilder()
                    .withStaticLabel(new Label("label1", "value1"))
                    .withDynamicLabelNames("label1");

            assertThatThrownBy(builder::build)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("conflicts with a static label");
        }

        @Test
        void testToString() {
            M metric = emptyMetricBuilder()
                    .withDescription("desc")
                    .withUnit(Unit.BYTE_UNIT)
                    .withStaticLabel(new Label("static_label", "static_value"))
                    .withDynamicLabelNames("dynamic_label")
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
}
