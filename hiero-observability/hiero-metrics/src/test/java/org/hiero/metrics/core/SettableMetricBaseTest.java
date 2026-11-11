// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.hiero.metrics.ThreadUtils;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

public abstract class SettableMetricBaseTest<M extends SettableMetric<?, ?>, B extends SettableMetric.Builder<?, B, M>>
        extends MetricBaseTest<M, B> {

    @Test
    void testSnapshotChangesOnlyOnUpdate() {
        M metric = emptyMetricBuilder().addDynamicLabelNames("label").build();
        MetricSnapshot snapshot = metric.snapshot();
        metric.getOrCreateLabeled("label", "a");
        snapshot.update();

        Iterator<MeasurementSnapshot> export = snapshot.iterator();
        metric.getOrCreateLabeled("label", "b");
        assertThat(labels(snapshot)).as("not visible before update").containsExactly("[a]");

        snapshot.update();
        List<String> seen = new ArrayList<>();
        export.forEachRemaining(s -> seen.add(s.getDynamicLabelValues().toString()));
        assertThat(seen).as("started iteration is not affected").containsExactly("[a]");
        assertThat(labels(snapshot)).containsExactly("[a]", "[b]");
    }

    @Test
    void testSnapshotIsSortedByLabelValues() {
        M metric = emptyMetricBuilder().addDynamicLabelNames("label").build();
        metric.getOrCreateLabeled("label", "b");
        metric.getOrCreateLabeled("label", "2");
        metric.getOrCreateLabeled("label", "a");
        metric.getOrCreateLabeled("label", "10");

        assertThat(exportedLabels(metric))
                .as("values are compared as strings")
                .containsExactly("[10]", "[2]", "[a]", "[b]");
    }

    @Test
    void testSnapshotIsSortedByLabelNamesAndThenValues() {
        M metric = emptyMetricBuilder().addDynamicLabelNames("type", "node").build();
        metric.getOrCreateLabeled("type", "a", "node", "2");
        metric.getOrCreateLabeled("type", "b", "node", "1");
        metric.getOrCreateLabeled("node", "1", "type", "a");

        assertThat(exportedLabels(metric)).containsExactly("[1,a]", "[1,b]", "[2,a]");
    }

    @Test
    void testMeasurementSnapshotIsReusedWhenSnapshotIsRebuilt() {
        M metric = emptyMetricBuilder().addDynamicLabelNames("label").build();
        metric.getOrCreateLabeled("label", "b");
        MeasurementSnapshot created = exported(metric).getFirst();

        metric.getOrCreateLabeled("label", "a");

        assertThat(exported(metric)).hasSize(2).last().isSameAs(created);
    }

    /**
     * Tests for accessing measurements in settable metrics.
     */
    @Nested
    class AccessMeasurementsTests {

        @Test
        void testNullCustomInitializerThrows() {
            M metric = emptyMetricBuilder().addDynamicLabelNames("label").build();

            assertThatThrownBy(() -> metric.getOrCreateLabeled(null, new String[] {"label", "value"}))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("custom measurement initializer must not be null");
        }

        @Test
        void testGetOrCreateLabeledWithCustomInitializerThrowsWhenNoDynamicLabelsDefined() {
            M metric = emptyMetricBuilder().build();

            assertThatThrownBy(() -> metric.getOrCreateLabeled(null, new String[] {}))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("This metric has no dynamic labels, so you must call getOrCreateNotLabeled()");
        }

        @Test
        void testGetOrCreateNotLabeledThrowsWhenDynamicLabelsDefined() {
            M metric = emptyMetricBuilder().addDynamicLabelNames("label").build();

            assertThatThrownBy(metric::getOrCreateNotLabeled)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("This metric has dynamic labels, so you must call getOrCreateLabeled()");
        }

        @Test
        void testNoDynamicLabelsMeasurementAccessDifferentWay() {
            M metric = emptyMetricBuilder().build();

            Object measurement1 = metric.getOrCreateNotLabeled();
            Object measurement2 = metric.getOrCreateLabeled();

            assertThat(measurement1).isNotNull();
            assertThat(measurement2)
                    .as("Accessing with empty labels should also work")
                    .isNotNull();
            assertThat(measurement1).isSameAs(measurement2);
            assertThat(measurement1).isSameAs(metric.getOrCreateNotLabeled());
        }

        @Test
        void testSingleDynamicLabelMeasurementsAccess() {
            M metric = emptyMetricBuilder()
                    // just to ensure static labels don't interfere
                    .addStaticLabels(new Label("environment", "test"))
                    .addDynamicLabelNames("label")
                    .build();

            Object measurement1 = metric.getOrCreateLabeled("label", "value1");
            Object measurement2 = metric.getOrCreateLabeled("label", "value2");
            Object measurement3 = metric.getOrCreateLabeled("label", "value1");

            assertThat(measurement1).isNotNull();
            assertThat(measurement2).isNotNull();
            assertThat(measurement3).isNotNull();

            assertThat(measurement1).isSameAs(measurement3);
            assertThat(measurement1).isNotSameAs(measurement2);
        }

        @Test
        void testMeasurementAccessSameLabelsDifferentOrder() {
            M metric = emptyMetricBuilder()
                    // just to ensure static labels don't interfere
                    .addStaticLabels(new Label("environment", "test"))
                    .addDynamicLabelNames("l1", "l2", "l3")
                    .build();

            Object measurement = metric.getOrCreateLabeled("l1", "a", "l2", "b", "l3", "c");
            assertThat(measurement).isNotNull();

            assertThat(measurement).isSameAs(metric.getOrCreateLabeled("l1", "a", "l3", "c", "l2", "b"));
            assertThat(measurement).isSameAs(metric.getOrCreateLabeled("l2", "b", "l1", "a", "l3", "c"));
            assertThat(measurement).isSameAs(metric.getOrCreateLabeled("l2", "b", "l3", "c", "l1", "a"));
            assertThat(measurement).isSameAs(metric.getOrCreateLabeled("l3", "c", "l2", "b", "l1", "a"));
            assertThat(measurement).isSameAs(metric.getOrCreateLabeled("l3", "c", "l1", "a", "l2", "b"));
        }

        @Test
        void testMeasurementsAccessByDifferentLabels() {
            M metric = emptyMetricBuilder()
                    // just to ensure static labels don't interfere
                    .addStaticLabels(new Label("environment", "test"))
                    .addDynamicLabelNames("l3", "l1", "l2")
                    .build();

            Object measurement1 = metric.getOrCreateLabeled("l1", "a", "l2", "a", "l3", "a");
            // single value difference
            Object measurement2 = metric.getOrCreateLabeled("l1", "a", "l2", "a", "l3", "b");
            // different order of labels
            Object measurement3 = metric.getOrCreateLabeled("l2", "a", "l1", "a", "l3", "a");
            // two values difference
            Object measurement4 = metric.getOrCreateLabeled("l1", "a", "l2", "b", "l3", "b");
            // three values difference
            Object measurement5 = metric.getOrCreateLabeled("l1", "b", "l2", "b", "l3", "b");

            assertThat(measurement1).isNotNull();
            assertThat(measurement2).isNotNull();
            assertThat(measurement3).isNotNull();
            assertThat(measurement4).isNotNull();
            assertThat(measurement5).isNotNull();

            assertThat(measurement1)
                    .as("same values but other order must be the same measurement")
                    .isSameAs(measurement3);
            assertThat(measurement1).isNotSameAs(measurement2);
            assertThat(measurement1).isNotSameAs(measurement4);
            assertThat(measurement1).isNotSameAs(measurement5);

            assertThat(measurement2).isNotSameAs(measurement3);
            assertThat(measurement2).isNotSameAs(measurement4);
            assertThat(measurement2).isNotSameAs(measurement5);

            assertThat(measurement3).isNotSameAs(measurement4);
            assertThat(measurement3).isNotSameAs(measurement5);

            assertThat(measurement4).isNotSameAs(measurement5);
        }

        @Test
        void testSingleDynamicLabelAccessMeasurementsThrows() {
            M metric = emptyMetricBuilder()
                    // just to ensure static labels don't interfere
                    .addStaticLabels(new Label("environment", "test"))
                    .addDynamicLabelNames("label")
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
                    .hasMessage("Expected 1 labels, got 0");

            assertThatThrownBy(() -> metric.getOrCreateLabeled("label", "value", "extraLabel", "extraValue"))
                    .as("accessing getOrCreateLabeled() with labels should be IAE")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Expected 1 labels, got 2");

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
        void testRepeatableLabeledMeasurementAccessAfterArrayModification() {
            M metric = emptyMetricBuilder()
                    .addDynamicLabelNames("label1", "label2")
                    .build();

            String[] labelsAndValues = new String[] {"label1", "value1", "label2", "value2"};
            Object measurement1 = metric.getOrCreateLabeled(labelsAndValues);

            // modify the array after the call
            labelsAndValues[1] = "modifiedValue";

            Object measurement2 = metric.getOrCreateLabeled("label1", "value1", "label2", "value2");

            assertThat(measurement1).isNotNull();
            assertThat(measurement2).isNotNull();
            assertThat(measurement1).isSameAs(measurement2);
        }

        @Test
        void testConcurrentAccessNotLabeledMeasurement() throws InterruptedException {
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
            assertThat(exported(metric)).hasSize(1);
        }

        @Test
        void testConcurrentAccessLabeledMeasurement() throws InterruptedException {
            M metric = emptyMetricBuilder().addDynamicLabelNames("label").build();

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
            assertThat(exported(metric)).hasSize(1);
        }

        @Test
        void testConcurrentCreationOfDifferentLabelsAddsAllSnapshots() throws InterruptedException {
            M metric = emptyMetricBuilder().addDynamicLabelNames("label").build();
            int threadCount = 10;

            ThreadUtils.runConcurrentAndWait(
                    threadCount,
                    Duration.ofMillis(300),
                    threadIdx -> () -> metric.getOrCreateLabeled("label", "v" + threadIdx));

            assertThat(exportedLabels(metric))
                    .containsExactlyInAnyOrderElementsOf(IntStream.range(0, threadCount)
                            .mapToObj(i -> "[v" + i + "]")
                            .toList());
        }
    }

    /**
     * Tests for removing measurements of settable metrics.
     */
    @Nested
    class RemoveMeasurementsTests {

        @Test
        void testRemoveIfThrowsWhenNoDynamicLabelsDefined() {
            M metric = emptyMetricBuilder().build();

            assertThatThrownBy(() -> metric.removeIf(_ -> true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(
                            "This metric has no dynamic labels, so it can be removed only from the metric registry");
        }

        @Test
        void testRemoveIfNullFilterThrows() {
            M metric = emptyMetricBuilder().addDynamicLabelNames("label").build();

            assertThatThrownBy(() -> metric.removeIf(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("filter must not be null");
        }

        @Test
        void testRemoveIfByLabelValue() {
            M metric = emptyMetricBuilder().addDynamicLabelNames("node", "type").build();
            Object kept = metric.getOrCreateLabeled("node", "1", "type", "a");
            metric.getOrCreateLabeled("node", "3", "type", "a");
            metric.getOrCreateLabeled("node", "3", "type", "b");

            assertThat(metric.removeIf(labelValues -> "3".equals(labelValues.get("node"))))
                    .isEqualTo(2);
            assertThat(metric.removeIf(labelValues -> "3".equals(labelValues.get("node"))))
                    .as("nothing left to remove")
                    .isZero();
            assertThat(metric.getOrCreateLabeled("node", "1", "type", "a")).isSameAs(kept);
            assertThat(exportedLabels(metric)).containsExactly("[1,a]");
        }

        @Test
        void testRemovedMeasurementIsCreatedAgain() {
            M metric = emptyMetricBuilder().addDynamicLabelNames("label").build();
            Object removed = metric.getOrCreateLabeled("label", "a");

            assertThat(metric.removeIf(_ -> true)).isEqualTo(1);

            assertThat(metric.getOrCreateLabeled("label", "a")).isNotSameAs(removed);
        }

        @Test
        void testSnapshotDropsRemovedAndSortsCreatedAgain() {
            M metric = emptyMetricBuilder().addDynamicLabelNames("label").build();
            MetricSnapshot snapshot = metric.snapshot();
            metric.getOrCreateLabeled("label", "a");
            metric.getOrCreateLabeled("label", "b");
            snapshot.update();

            Iterator<MeasurementSnapshot> export = snapshot.iterator();
            metric.removeIf(labelValues -> "a".equals(labelValues.get("label")));
            metric.getOrCreateLabeled("label", "c");
            snapshot.update();

            List<String> seen = new ArrayList<>();
            export.forEachRemaining(s -> seen.add(s.getDynamicLabelValues().toString()));
            assertThat(seen).as("started iteration is not affected").containsExactly("[a]", "[b]");
            assertThat(labels(snapshot)).containsExactly("[b]", "[c]");

            metric.getOrCreateLabeled("label", "a");
            snapshot.update();
            assertThat(labels(snapshot)).containsExactly("[a]", "[b]", "[c]");
        }

        @Test
        void testRemoveIfCallsFilterWithoutLock() {
            M metric = emptyMetricBuilder().addDynamicLabelNames("label").build();
            metric.getOrCreateLabeled("label", "a");
            AtomicInteger removedByOtherThread = new AtomicInteger();

            int removed = metric.removeIf(_ -> {
                // removing the same measurement in another thread would block, if the filter held the map bin lock
                removedByOtherThread.set(CompletableFuture.supplyAsync(() -> metric.removeIf(_ -> true))
                        .orTimeout(1, TimeUnit.SECONDS)
                        .join());
                return true;
            });

            assertThat(removedByOtherThread).hasValue(1);
            assertThat(removed).as("already removed by the other thread").isZero();
        }

        @Test
        void testConcurrentCreateAndRemoveKeepSnapshotConsistent() throws InterruptedException {
            M metric = emptyMetricBuilder().addDynamicLabelNames("label").build();
            MetricSnapshot snapshot = metric.snapshot();
            final int threadCount = 4;
            final int iterations = 20_000;
            final int labelsCount = 5;

            // an export rebuilt during concurrent changes may iterate a measurement removed and created again twice,
            // so every export is checked for duplicates
            ThreadUtils.runConcurrentAndCheck(
                    threadCount,
                    Duration.ofSeconds(10),
                    threadIdx -> () -> {
                        for (int i = 0; i < iterations; i++) {
                            final String label = "v" + (i % labelsCount);
                            if ((i + threadIdx) % 2 == 0) {
                                metric.getOrCreateLabeled("label", label);
                            } else {
                                metric.removeIf(labelValues -> label.equals(labelValues.get("label")));
                            }
                        }
                    },
                    () -> {
                        snapshot.update();
                        assertThat(labels(snapshot))
                                .as("export has duplicate labels")
                                .doesNotHaveDuplicates();
                    });

            List<String> exported = exportedLabels(metric);
            assertThat(exported).doesNotHaveDuplicates();
            assertThat(metric.removeIf(_ -> true))
                    .as("exported measurements are the existing ones")
                    .isEqualTo(exported.size());
            assertThat(exportedLabels(metric))
                    .as("no removed measurement left in the snapshot")
                    .isEmpty();
        }
    }

    private static List<MeasurementSnapshot> exported(Metric<?> metric) {
        MetricSnapshot snapshot = metric.snapshot();
        snapshot.update();
        List<MeasurementSnapshot> exported = new ArrayList<>();
        snapshot.forEach(exported::add);
        return exported;
    }

    private static List<String> exportedLabels(Metric<?> metric) {
        metric.snapshot().update();
        return labels(metric.snapshot());
    }

    private static List<String> labels(MetricSnapshot snapshot) {
        List<String> labels = new ArrayList<>();
        snapshot.forEach(s -> labels.add(s.getDynamicLabelValues().toString()));
        return labels;
    }
}
