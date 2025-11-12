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
import org.hiero.metrics.api.core.MetricsFacade;
import org.hiero.metrics.api.utils.Unit;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

public abstract class AbstractMetricBaseTest<M extends Metric, B extends Metric.Builder<B, M>> {

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
            assertThat(builder.getConstantLabels())
                    .as("Constant labels are empty by default")
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
        @MethodSource("org.hiero.metrics.TestUtils#invalidNames")
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
        @MethodSource("org.hiero.metrics.TestUtils#invalidNames")
        void testInvalidDynamicLabelNamesThrows(String invalidDynamicLabelName) {
            B builder = emptyMetricBuilder();

            assertThatThrownBy(() -> builder.withDynamicLabelNames(invalidDynamicLabelName))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void testNullConstantLabelsArrayThrows() {
            B builder = emptyMetricBuilder();
            Label[] nullArray = null;

            assertThatThrownBy(() -> builder.withConstantLabels(nullArray))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("labels must not be null");
        }

        @Test
        void testNullConstantLabelsCollectionThrows() {
            B builder = emptyMetricBuilder();
            Collection<Label> nullCollection = null;

            assertThatThrownBy(() -> builder.withConstantLabels(nullCollection))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("labels must not be null");
        }

        @Test
        void testConstantLabelSameAsMetricNameThrows() {
            B builder = emptyMetricBuilder();

            assertThatThrownBy(() -> builder.withConstantLabel(new Label("test_metric", "value")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Label name must not be the same as metric name");
        }

        @Test
        void testDuplicateConstantLabelNamesThrows() {
            B builder = emptyMetricBuilder().withConstantLabel(new Label("label1", "value1"));

            assertThatThrownBy(() -> builder.withConstantLabel(new Label("label1", "value2")))
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
            assertThat(metric.metadata()).isNotNull();
            assertThat(metric.metadata().name()).isEqualTo(DEFAULT_NAME);
            assertThat(metric.metadata().metricType()).isEqualTo(metricType());
            assertThat(metric.metadata().unit()).isNotNull();
            assertThat(metric.metadata().unit()).isEmpty();
            assertThat(metric.metadata().description()).isNotNull();
            assertThat(metric.metadata().description()).isEmpty();

            // labels are empty by default
            assertThat(metric.constantLabels())
                    .as("Constant labels are empty by default")
                    .isEmpty();
            assertThat(metric.dynamicLabelNames())
                    .as("Dynamic label names are empty by default")
                    .isEmpty();
        }

        @Test
        void testMetadataIsSameInstance() {
            M metric = emptyMetricBuilder().build();

            assertThat(metric.metadata()).isSameAs(metric.metadata());
        }

        @Test
        void testMetricImmutabilityAfterBuilderModification() {
            String description = "description";
            String unit = "unit";
            String constantLabelName = "const_label";
            String dynamicLabelName = "dynamic_label";

            B builder = emptyMetricBuilder()
                    .withDescription(description)
                    .withUnit(unit)
                    .withConstantLabel(new Label(constantLabelName, "value"))
                    .withDynamicLabelNames(dynamicLabelName);

            M metric = builder.build();

            // Modify builder after building the metric
            builder.withDescription(description + "_")
                    .withUnit(unit + "_")
                    .withConstantLabel(new Label(constantLabelName + "_", "value"))
                    .withDynamicLabelNames(dynamicLabelName + "_");

            // Metric metadata should remain unchanged
            assertThat(metric.metadata().description()).isEqualTo(description);
            assertThat(metric.metadata().unit()).isEqualTo(unit);
            assertThat(metric.constantLabels()).containsExactly(new Label(constantLabelName, "value"));
            assertThat(metric.dynamicLabelNames()).containsExactly(dynamicLabelName);
        }

        @Test
        void testWithNullDescription() {
            M metric = emptyMetricBuilder().withDescription(null).build();

            assertThat(metric.metadata().description()).isNotNull();
            assertThat(metric.metadata().description()).isEmpty();
        }

        @ParameterizedTest
        @MethodSource("org.hiero.metrics.TestUtils#invalidNames")
        void testWithDescriptionAsInvalidName(String description) {
            M metric = emptyMetricBuilder().withDescription(description).build();

            assertThat(metric.metadata().description())
                    .as("Description could be anything")
                    .isEqualTo(description);
        }

        @ParameterizedTest
        @MethodSource("org.hiero.metrics.TestUtils#validNames")
        void testWithDescriptionAsValidName(String description) {
            M metric = emptyMetricBuilder().withDescription(description).build();

            assertThat(metric.metadata().description())
                    .as("Description could be anything")
                    .isEqualTo(description);
        }

        @Test
        void testDescriptionOverride() {
            M metric = emptyMetricBuilder()
                    .withDescription("first description")
                    .withDescription("second description")
                    .build();

            assertThat(metric.metadata().description()).isEqualTo("second description");
        }

        @Test
        void testWithNullStringUnit() {
            String nullUnit = null;
            M metric = emptyMetricBuilder().withUnit(nullUnit).build();

            assertThat(metric.metadata().unit()).isNotNull();
            assertThat(metric.metadata().unit()).isEmpty();
        }

        @Test
        void testWithBlankStringUnit() {
            M metric = emptyMetricBuilder().withUnit("").build();

            assertThat(metric.metadata().unit()).isNotNull();
            assertThat(metric.metadata().unit()).isEmpty();
        }

        @ParameterizedTest
        @MethodSource("org.hiero.metrics.TestUtils#validNames")
        void testWithValidStringUnit(String validUnit) {
            M metric = emptyMetricBuilder().withUnit(validUnit).build();

            assertThat(metric.metadata().unit()).isEqualTo(validUnit);
        }

        @Test
        void testUnitOverride() {
            M metric = emptyMetricBuilder()
                    .withUnit("custom_unit")
                    .withUnit(Unit.BYTE_UNIT)
                    .build();

            assertThat(metric.metadata().unit()).isEqualTo(Unit.BYTE_UNIT.toString());
        }

        @ParameterizedTest
        @EnumSource(Unit.class)
        void testWithValidEnumUnit(Unit unit) {
            M metric = emptyMetricBuilder().withUnit(unit).build();

            assertThat(metric.metadata().unit()).isEqualTo(unit.toString());
        }

        @Test
        void testRegister() {
            MetricRegistry registry = MetricsFacade.createRegistry();
            B builder = emptyMetricBuilder();

            builder.register(registry);

            M metric = registry.getMetric(builder.key());
            assertThat(metric.metadata().name()).isEqualTo("test_metric");
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
        @MethodSource("org.hiero.metrics.TestUtils#validNames")
        void testSingleValidDynamicLabelName(String validLabelName) {
            M metric =
                    emptyMetricBuilder().withDynamicLabelNames(validLabelName).build();

            assertThat(metric.dynamicLabelNames()).containsExactly(validLabelName);
        }

        @Test
        void testMultipleValidDynamicLabelName() {
            M metric = emptyMetricBuilder()
                    .withDynamicLabelNames(TestUtils.validNames())
                    .build();

            assertThat(metric.dynamicLabelNames()).containsExactlyInAnyOrder(TestUtils.validNames());
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
        void testEmptyConstantLabelsArray() {
            M metric = emptyMetricBuilder().withConstantLabels().build();

            assertThat(metric.constantLabels()).isNotNull();
            assertThat(metric.constantLabels()).isEmpty();
        }

        @Test
        void testEmptyConstantLabelsCollection() {
            M metric =
                    emptyMetricBuilder().withConstantLabels(new ArrayList<>()).build();

            assertThat(metric.constantLabels()).isNotNull();
            assertThat(metric.constantLabels()).isEmpty();
        }

        @Test
        void testConstantLabelsSameInstance() {
            M metric = emptyMetricBuilder()
                    .withConstantLabel(new Label("label1", "value"))
                    .build();

            assertThat(metric.dynamicLabelNames()).isSameAs(metric.dynamicLabelNames());
        }

        @ParameterizedTest
        @MethodSource("org.hiero.metrics.TestUtils#validNames")
        void testSingleValidConstantLabelName(String validLabelName) {
            Label label = new Label(validLabelName, "value");
            M metric = emptyMetricBuilder().withConstantLabel(label).build();

            assertThat(metric.constantLabels()).containsExactly(label);
        }

        @Test
        void testMultipleValidConstantLabels() {
            List<Label> labels = Stream.of(TestUtils.validNames())
                    .map(l -> new Label(l, "value"))
                    .toList();

            M metric = emptyMetricBuilder().withConstantLabels(labels).build();

            assertThat(metric.constantLabels()).containsExactlyInAnyOrderElementsOf(labels);
        }

        @Test
        void testConstantLabelsImmutable() {
            M metric = emptyMetricBuilder()
                    .withConstantLabel(new Label("label1", "value"))
                    .build();

            List<Label> constantLabels = metric.constantLabels();

            assertThatThrownBy(() -> constantLabels.add(new Label("label2", "value")))
                    .isInstanceOf(UnsupportedOperationException.class);

            assertThatThrownBy(() -> constantLabels.remove(new Label("label1", "value")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void testConstantLabelsOrder() {
            M metric = emptyMetricBuilder()
                    .withConstantLabel(new Label("z_label", "value"))
                    .withConstantLabel(new Label("a_label", "value"))
                    .withConstantLabel(new Label("m_label", "value"))
                    .build();

            List<String> actual =
                    metric.constantLabels().stream().map(Label::name).toList();
            assertThat(actual).containsExactly("a_label", "m_label", "z_label");
        }

        @Test
        void testDuplicateConstantLabelAndDynamicLabelThrowsOnBuild() {
            B builder = emptyMetricBuilder()
                    .withConstantLabel(new Label("label1", "value1"))
                    .withDynamicLabelNames("label1");

            assertThatThrownBy(builder::build)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("conflicts with a constant label");
        }
    }
}
