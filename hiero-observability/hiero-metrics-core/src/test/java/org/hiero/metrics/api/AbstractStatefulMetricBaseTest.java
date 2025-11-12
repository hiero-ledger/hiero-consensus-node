// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.hiero.metrics.api.core.Label;
import org.hiero.metrics.api.core.StatefulMetric;
import org.hiero.metrics.test.fixtures.ThreadUtils;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

public abstract class AbstractStatefulMetricBaseTest<
                M extends StatefulMetric<?, ?>, B extends StatefulMetric.Builder<?, ?, B, M>>
        extends AbstractMetricBaseTest<M, B> {

    @Nested
    class DataPointsTests {

        @Test
        void testGetOrCreateNotLabeledThrowsWhenDynamicLabelsDefined() {
            M metric = emptyMetricBuilder().withDynamicLabelNames("label").build();

            assertThatThrownBy(metric::getOrCreateNotLabeled)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("This metric has dynamic labels, so you must call getOrCreateLabeled()");
        }

        @Test
        void testNoDynamicLabelsDataPointAccess() {
            M metric = emptyMetricBuilder().build();

            Object datapoint1 = metric.getOrCreateNotLabeled();
            Object datapoint2 = metric.getOrCreateLabeled();

            assertThat(datapoint1).isNotNull();
            assertThat(datapoint2)
                    .as("Accessing with empty labels should also work")
                    .isNotNull();
            assertThat(datapoint1).isSameAs(datapoint2);
            assertThat(datapoint1).isSameAs(metric.getOrCreateNotLabeled());
        }

        @Test
        void testSingleDynamicLabelDataPointsAccess() {
            M metric = emptyMetricBuilder()
                    // just to ensure constant labels don't interfere
                    .withConstantLabel(new Label("environment", "test"))
                    .withDynamicLabelNames("label")
                    .build();

            Object datapoint1 = metric.getOrCreateLabeled("label", "value1");
            Object datapoint2 = metric.getOrCreateLabeled("label", "value2");
            Object datapoint3 = metric.getOrCreateLabeled("label", "value1");

            assertThat(datapoint1).isNotNull();
            assertThat(datapoint2).isNotNull();
            assertThat(datapoint3).isNotNull();

            assertThat(datapoint1).isSameAs(datapoint3);
            assertThat(datapoint1).isNotSameAs(datapoint2);
        }

        @Test
        void testMultipleDynamicLabelDataPointsAccess() {
            M metric = emptyMetricBuilder()
                    // just to ensure constant labels don't interfere
                    .withConstantLabel(new Label("environment", "test"))
                    .withDynamicLabelNames("label3", "label1", "label2")
                    .build();

            Object datapoint1 = metric.getOrCreateLabeled("label1", "a", "label2", "a", "label3", "a");
            // single value difference
            Object datapoint2 = metric.getOrCreateLabeled("label1", "a", "label2", "a", "label3", "b");
            // different order of labels
            Object datapoint3 = metric.getOrCreateLabeled("label2", "a", "label1", "a", "label3", "a");
            // two values difference
            Object datapoint4 = metric.getOrCreateLabeled("label1", "a", "label2", "b", "label3", "b");
            // three values difference
            Object datapoint5 = metric.getOrCreateLabeled("label1", "b", "label2", "b", "label3", "b");

            assertThat(datapoint1).isNotNull();
            assertThat(datapoint2).isNotNull();
            assertThat(datapoint3).isNotNull();
            assertThat(datapoint4).isNotNull();
            assertThat(datapoint5).isNotNull();

            assertThat(datapoint1)
                    .as("same values but other order must be the same datapoint")
                    .isSameAs(datapoint3);
            assertThat(datapoint1).isNotSameAs(datapoint2);
            assertThat(datapoint1).isNotSameAs(datapoint4);
            assertThat(datapoint1).isNotSameAs(datapoint5);

            assertThat(datapoint2).isNotSameAs(datapoint3);
            assertThat(datapoint2).isNotSameAs(datapoint4);
            assertThat(datapoint2).isNotSameAs(datapoint5);

            assertThat(datapoint3).isNotSameAs(datapoint4);
            assertThat(datapoint3).isNotSameAs(datapoint5);

            assertThat(datapoint4).isNotSameAs(datapoint5);
        }

        @Test
        void testSingleDynamicLabelAccessDataPointsThrows() {
            M metric = emptyMetricBuilder()
                    // just to ensure constant labels don't interfere
                    .withConstantLabel(new Label("environment", "test"))
                    .withDynamicLabelNames("label")
                    .build();

            String[] nullArray = null;
            assertThatThrownBy(() -> metric.getOrCreateLabeled(nullArray))
                    .as("accessing with null array should be NPE")
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("Label names and values must not be null");

            assertThatThrownBy(() -> metric.getOrCreateLabeled("label"))
                    .as("accessing with not pairs of elements should be IAE")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Label names and values must be in pairs");

            assertThatThrownBy(metric::getOrCreateLabeled)
                    .as("accessing getOrCreateLabeled() less labels should be IAE")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Expected 1 label names and values, got 0");

            assertThatThrownBy(() -> metric.getOrCreateLabeled("label", "value", "extraLabel", "extraValue"))
                    .as("accessing getOrCreateLabeled() with labels should be IAE")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Expected 1 label names and values, got 2");

            assertThatThrownBy(() -> metric.getOrCreateLabeled("label", null))
                    .as("accessing with null value should be NPE")
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Label value must not be null");

            assertThatThrownBy(() -> metric.getOrCreateLabeled(new String[] {null, "value"}))
                    .as("accessing with null label name should be IAE")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Missing label name: label");

            assertThatThrownBy(() -> metric.getOrCreateLabeled("label1", "value"))
                    .as("accessing with wrong label name should be IAE")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Missing label name: label");
        }

        @Test
        void testLabeledDataPointAccessAfterArrayModification() {
            M metric = emptyMetricBuilder()
                    .withDynamicLabelNames("label1", "label2")
                    .build();

            String[] labelsAndValues = new String[] {"label1", "value1", "label2", "value2"};
            Object datapoint1 = metric.getOrCreateLabeled(labelsAndValues);

            // modify the array after the call
            labelsAndValues[1] = "modifiedValue";

            Object datapoint2 = metric.getOrCreateLabeled("label1", "value1", "label2", "value2");

            assertThat(datapoint1).isNotNull();
            assertThat(datapoint2).isNotNull();
            assertThat(datapoint1).isSameAs(datapoint2);
        }

        @Test
        void testConcurrentAccessNotLabeledDataPoint() throws InterruptedException {
            M metric = emptyMetricBuilder().build();
            int threadCount = 10;
            final Object[] results = new Object[threadCount];

            ThreadUtils.runConcurrentAndWait(
                    threadCount,
                    Duration.ofMillis(300),
                    threadIdx -> () -> results[threadIdx] = metric.getOrCreateNotLabeled());

            for (int i = 1; i < threadCount; i++) {
                assertThat(results[i]).isNotNull();
                assertThat(results[i]).isSameAs(results[0]);
            }
        }

        @Test
        void testConcurrentAccessLabeledDataPoint() throws InterruptedException {
            M metric = emptyMetricBuilder().withDynamicLabelNames("label").build();

            int threadCount = 10;
            final Object[] results = new Object[threadCount];
            final String[] namesAndValues = new String[] {"label", "concurrentValue"};

            ThreadUtils.runConcurrentAndWait(
                    threadCount,
                    Duration.ofMillis(300),
                    threadIdx -> () -> results[threadIdx] = metric.getOrCreateLabeled(namesAndValues));

            for (int i = 1; i < threadCount; i++) {
                assertThat(results[i]).isNotNull();
                assertThat(results[i]).isSameAs(results[0]);
            }
        }
    }
}
