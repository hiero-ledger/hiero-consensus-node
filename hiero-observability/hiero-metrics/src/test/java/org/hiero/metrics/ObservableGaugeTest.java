// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import org.hiero.metrics.core.Label;
import org.hiero.metrics.core.MeasurementSnapshot;
import org.hiero.metrics.core.MetricBaseTest;
import org.hiero.metrics.core.MetricKey;
import org.hiero.metrics.core.MetricRegistry;
import org.hiero.metrics.core.MetricSnapshot;
import org.hiero.metrics.core.MetricSnapshotVerifier;
import org.hiero.metrics.core.MetricType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

public class ObservableGaugeTest extends MetricBaseTest<ObservableGauge, ObservableGauge.Builder> {

    @Override
    protected MetricType metricType() {
        return MetricType.GAUGE;
    }

    @Override
    protected ObservableGauge.Builder emptyMetricBuilder(String name) {
        return ObservableGauge.builder(name);
    }

    @Test
    void testSnapshotChangesOnlyOnUpdate() {
        ObservableGauge gauge =
                emptyMetricBuilder().addDynamicLabelNames("label").build();
        gauge.observe(() -> 1L, "label", "a");

        Iterator<MeasurementSnapshot> export = exportedSnapshotOf(gauge).iterator();
        gauge.observe(() -> 2L, "label", "b");
        assertThat(labels(snapshotOf(gauge))).as("not visible before update").containsExactly("[a]");

        assertThat(exportedLabels(gauge)).containsExactly("[a]", "[b]");
        List<String> seen = new ArrayList<>();
        export.forEachRemaining(s -> seen.add(s.getDynamicLabelValues().toString()));
        assertThat(seen).as("started iteration is not affected").containsExactly("[a]");
    }

    @Test
    void testSnapshotIsSortedByLabelValues() {
        ObservableGauge gauge =
                emptyMetricBuilder().addDynamicLabelNames("label").build();
        gauge.observe(() -> 1L, "label", "b");
        gauge.observe(() -> 2.0, "label", "a");
        gauge.observe(() -> 3L, "label", "c");

        assertThat(exportedLabels(gauge)).containsExactly("[a]", "[b]", "[c]");
    }

    @Test
    void testRemoveIfRemovesMatchingSuppliers() {
        ObservableGauge gauge =
                emptyMetricBuilder().addDynamicLabelNames("node", "type").build();
        gauge.observe(() -> 1L, "node", "1", "type", "a");
        gauge.observe(() -> 2L, "node", "3", "type", "a");
        gauge.observe(() -> 3.0, "node", "3", "type", "b");

        assertThat(gauge.removeIf(labelValues -> "3".equals(labelValues.get("node"))))
                .isEqualTo(2);
        assertThat(gauge.removeIf(labelValues -> "3".equals(labelValues.get("node"))))
                .as("nothing left to remove")
                .isZero();
        assertThat(exportedLabels(gauge)).containsExactly("[1,a]");

        // removed labels can be observed again
        gauge.observe(() -> 4L, "node", "3", "type", "a");
        assertThat(exportedLabels(gauge)).containsExactly("[1,a]", "[3,a]");
    }

    @Test
    void testRemoveIfDoesNotAffectStartedIteration() {
        ObservableGauge gauge =
                emptyMetricBuilder().addDynamicLabelNames("label").build();
        gauge.observe(() -> 1L, "label", "a");
        gauge.observe(() -> 2L, "label", "b");

        Iterator<MeasurementSnapshot> export = exportedSnapshotOf(gauge).iterator();
        gauge.removeIf(labelValues -> "a".equals(labelValues.get("label")));

        List<String> seen = new ArrayList<>();
        export.forEachRemaining(s -> seen.add(s.getDynamicLabelValues().toString()));
        assertThat(seen).containsExactly("[a]", "[b]");
        assertThat(exportedLabels(gauge)).containsExactly("[b]");
    }

    @Test
    void testRemoveIfThrowsWhenNoDynamicLabelsDefined() {
        ObservableGauge gauge = emptyMetricBuilder().build();

        assertThatThrownBy(() -> gauge.removeIf(_ -> true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("This metric has no dynamic labels, so it can be removed only from the metric registry");
    }

    @Test
    void testRemoveIfNullFilterThrows() {
        ObservableGauge gauge =
                emptyMetricBuilder().addDynamicLabelNames("label").build();

        assertThatThrownBy(() -> gauge.removeIf(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("filter must not be null");
    }

    @Test
    void testObserveDuplicateDoesNotCallSupplier() {
        ObservableGauge gauge =
                emptyMetricBuilder().addDynamicLabelNames("label").build();
        gauge.observe(() -> 1L, "label", "a");
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> gauge.observe(() -> (long) calls.incrementAndGet(), "label", "a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A measurement with the same label values already exists");
        assertThat(calls).hasValue(0);
    }

    @Test
    void testThrowingSupplierFailsExportsUntilRemoved() {
        ObservableGauge gauge =
                emptyMetricBuilder().addDynamicLabelNames("label").build();

        // observe does not call the supplier, so it does not reject one that throws
        gauge.observe(
                () -> {
                    throw new IllegalStateException("source closed");
                },
                "label",
                "a");

        assertThatThrownBy(() -> exportedLabels(gauge))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("source closed");
        assertThatThrownBy(() -> exportedLabels(gauge))
                .as("every export fails until the supplier is removed")
                .isInstanceOf(IllegalStateException.class);

        assertThat(gauge.removeIf(labelValues -> "a".equals(labelValues.get("label"))))
                .isEqualTo(1);
        assertThat(exportedLabels(gauge)).isEmpty();
        gauge.observe(() -> 1L, "label", "a");
        assertThat(exportedLabels(gauge)).containsExactly("[a]");
    }

    @Test
    void testSupplierIsCalledOnlyByExports() {
        ObservableGauge gauge =
                emptyMetricBuilder().addDynamicLabelNames("label").build();
        AtomicInteger calls = new AtomicInteger();

        gauge.observe(() -> (long) calls.incrementAndGet(), "label", "a");
        assertThat(calls).as("not called by observe").hasValue(0);

        new MetricSnapshotVerifier(gauge).add(1L, "label", "a").verify();
        new MetricSnapshotVerifier(gauge).add(2L, "label", "a").verify();
        assertThat(calls).as("called once by each export").hasValue(2);
    }

    @Test
    void testRemovedSupplierIsNotCalledByLaterExports() {
        ObservableGauge gauge =
                emptyMetricBuilder().addDynamicLabelNames("label").build();
        AtomicInteger removedCalls = new AtomicInteger();
        gauge.observe(() -> (long) removedCalls.incrementAndGet(), "label", "a");
        gauge.observe(() -> 2L, "label", "b");
        // exported once, so the snapshot of the last export holds the supplier when it is removed
        exportedSnapshotOf(gauge);

        assertThat(gauge.removeIf(labelValues -> "a".equals(labelValues.get("label"))))
                .isEqualTo(1);
        final int callsAtRemoval = removedCalls.get();
        assertThat(callsAtRemoval).as("called by the export before removal").isEqualTo(1);

        new MetricSnapshotVerifier(gauge).add(2L, "label", "b").verify();
        assertThat(removedCalls).as("removed supplier is not called").hasValue(callsAtRemoval);
    }

    @Test
    void testObserveDuringExportIsExportedByNextExport() {
        ObservableGauge gauge =
                emptyMetricBuilder().addDynamicLabelNames("label").build();
        AtomicInteger calls = new AtomicInteger();
        gauge.observe(
                () -> {
                    // the first call is made by the first export
                    if (calls.incrementAndGet() == 1) {
                        gauge.observe(() -> 2L, "label", "b");
                    }
                    return 1L;
                },
                "label",
                "a");

        assertThat(exportedLabels(gauge))
                .as("observed after the export rebuilt the list")
                .containsExactly("[a]");
        assertThat(exportedLabels(gauge)).containsExactly("[a]", "[b]");
    }

    @Test
    void testRemoveIfDuringExportIsExportedByNextExport() {
        ObservableGauge gauge =
                emptyMetricBuilder().addDynamicLabelNames("label").build();
        AtomicInteger calls = new AtomicInteger();
        gauge.observe(() -> 2L, "label", "b");
        gauge.observe(
                () -> {
                    // the first call is made by the first export
                    if (calls.incrementAndGet() == 1) {
                        gauge.removeIf(labelValues -> "b".equals(labelValues.get("label")));
                    }
                    return 1L;
                },
                "label",
                "a");

        assertThat(exportedLabels(gauge))
                .as("removed after the export rebuilt the list")
                .containsExactly("[a]", "[b]");
        assertThat(exportedLabels(gauge)).containsExactly("[a]");
    }

    @Test
    void testObserveWithoutExporterDoesNotCallSupplier() {
        MetricRegistry registry = MetricRegistry.builder().build();
        AtomicInteger calls = new AtomicInteger();
        LongSupplier supplier = () -> {
            calls.incrementAndGet();
            return 1L;
        };

        ObservableGauge gauge = emptyMetricBuilder()
                .addDynamicLabelNames("label")
                .observe(supplier, "label", "a")
                .register(registry);
        gauge.observe(supplier, "label", "b");

        assertThat(calls)
                .as("only exports call suppliers, and there is no exporter")
                .hasValue(0);
        MetricSnapshotVerifier.verifMetricHasNoSnapshot(gauge);
    }

    @Test
    void testRemoveIfCallsFilterWithoutLock() {
        ObservableGauge gauge =
                emptyMetricBuilder().addDynamicLabelNames("label").build();
        gauge.observe(() -> 1L, "label", "a");
        AtomicInteger removedByOtherThread = new AtomicInteger();

        int removed = gauge.removeIf(_ -> {
            // removing the same supplier in another thread would block, if the filter held the map bin lock
            removedByOtherThread.set(CompletableFuture.supplyAsync(() -> gauge.removeIf(_ -> true))
                    .orTimeout(1, TimeUnit.SECONDS)
                    .join());
            return true;
        });

        assertThat(removedByOtherThread).hasValue(1);
        assertThat(removed).as("already removed by the other thread").isZero();
    }

    @Test
    void testConcurrentObserveAndRemoveKeepSnapshotConsistent() throws InterruptedException {
        ObservableGauge gauge =
                emptyMetricBuilder().addDynamicLabelNames("label").build();
        final int threadCount = 4;
        final int iterations = 20_000;
        final List<String> values = collidingLabelValues();
        // all label values share one map bin, observed up front, so the race starts on a resized map with a tree bin
        values.forEach(value -> gauge.observe(() -> 1L, "label", value));

        // an export rebuilt during concurrent changes may iterate a supplier removed and observed again twice,
        // so every export is checked for duplicates
        ThreadUtils.runConcurrentAndCheck(
                threadCount,
                Duration.ofSeconds(10),
                threadIdx -> () -> {
                    for (int i = 0; i < iterations; i++) {
                        final String label = values.get(i % values.size());
                        if ((i + threadIdx) % 2 == 0) {
                            try {
                                gauge.observe(() -> 1L, "label", label);
                            } catch (IllegalArgumentException alreadyObserved) {
                                // another thread observed it first
                            }
                        } else {
                            gauge.removeIf(labelValues -> label.equals(labelValues.get("label")));
                        }
                    }
                },
                () -> assertThat(exportedLabels(gauge))
                        .as("export has duplicate labels")
                        .doesNotHaveDuplicates());

        // the first export after the race rebuilds only if a change is still marked
        List<MeasurementSnapshot> exported = exportedSnapshots(gauge);
        assertThat(exported)
                .extracting(snapshot -> snapshot.getDynamicLabelValues().toString())
                .doesNotHaveDuplicates();
        gauge.observe(() -> 0L, "label", "forced_rebuild");
        gauge.removeIf(labelValues -> "forced_rebuild".equals(labelValues.get("label")));
        assertThat(exported)
                .as("exported snapshots are the ones of the observed suppliers, so no change was lost")
                .usingElementComparator((first, second) -> first == second ? 0 : 1)
                .containsExactlyElementsOf(exportedSnapshots(gauge));
        assertThat(gauge.removeIf(_ -> true))
                .as("exported suppliers are the observed ones")
                .isEqualTo(exported.size());
        assertThat(exportedLabels(gauge))
                .as("no removed supplier left in the snapshot")
                .isEmpty();
    }

    private List<String> exportedLabels(ObservableGauge gauge) {
        return labels(exportedSnapshotOf(gauge));
    }

    private List<MeasurementSnapshot> exportedSnapshots(ObservableGauge gauge) {
        List<MeasurementSnapshot> snapshots = new ArrayList<>();
        exportedSnapshotOf(gauge).forEach(snapshots::add);
        return snapshots;
    }

    private static List<String> labels(MetricSnapshot snapshot) {
        List<String> labels = new ArrayList<>();
        snapshot.forEach(s -> labels.add(s.getDynamicLabelValues().toString()));
        return labels;
    }

    @Test
    void testNullNameMetricKey() {
        assertThatThrownBy(() -> ObservableGauge.key(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testNullMetricKeyBuilder() {
        assertThatThrownBy(() -> ObservableGauge.builder((MetricKey<ObservableGauge>) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("key must not be null");
    }

    @Test
    void testNullMetricNameBuilder() {
        assertThatThrownBy(() -> ObservableGauge.builder((String) null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testObserveThrows() {
        ObservableGauge metric = emptyMetricBuilder()
                // just to ensure static labels don't interfere
                .addStaticLabels(new Label("environment", "test"))
                .addDynamicLabelNames("label")
                .build();

        LongSupplier longSupplier = () -> 42L;
        DoubleSupplier doubleSupplier = () -> 3.14;
        String[] nullArray = null;

        assertThatThrownBy(() -> metric.observe((LongSupplier) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("value supplier must not be null");
        assertThatThrownBy(() -> metric.observe((DoubleSupplier) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("value supplier must not be null");

        assertThatThrownBy(() -> metric.observe(longSupplier, nullArray))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Label names and values must not be null");
        assertThatThrownBy(() -> metric.observe(doubleSupplier, nullArray))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Label names and values must not be null");

        assertThatThrownBy(() -> metric.observe(longSupplier, "label"))
                .as("accessing with not pairs of elements should be IAE")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Label names and values must be in pairs");
        assertThatThrownBy(() -> metric.observe(doubleSupplier, "label"))
                .as("accessing with not pairs of elements should be IAE")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Label names and values must be in pairs");

        assertThatThrownBy(() -> metric.observe(longSupplier))
                .as("accessing getOrCreateLabeled() less labels should be IAE")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Expected 1 labels, got 0");
        assertThatThrownBy(() -> metric.observe(doubleSupplier))
                .as("accessing getOrCreateLabeled() less labels should be IAE")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Expected 1 labels, got 0");

        assertThatThrownBy(() -> metric.observe(longSupplier, "label", "value", "extraLabel", "extraValue"))
                .as("accessing getOrCreateLabeled() with labels should be IAE")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Expected 1 labels, got 2");
        assertThatThrownBy(() -> metric.observe(doubleSupplier, "label", "value", "extraLabel", "extraValue"))
                .as("accessing getOrCreateLabeled() with labels should be IAE")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Expected 1 labels, got 2");

        assertThatThrownBy(() -> metric.observe(longSupplier, "label", null))
                .as("accessing with null value should be NPE")
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Label value must not be null");
        assertThatThrownBy(() -> metric.observe(doubleSupplier, "label", null))
                .as("accessing with null value should be NPE")
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Label value must not be null");

        assertThatThrownBy(() -> metric.observe(longSupplier, new String[] {null, "value"}))
                .as("accessing with null label name should be IAE")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing label name: label");
        assertThatThrownBy(() -> metric.observe(doubleSupplier, new String[] {null, "value"}))
                .as("accessing with null label name should be IAE")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing label name: label");

        assertThatThrownBy(() -> metric.observe(longSupplier, "label1", "value"))
                .as("accessing with wrong label name should be IAE")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing label name: label");
        assertThatThrownBy(() -> metric.observe(doubleSupplier, "label1", "value"))
                .as("accessing with wrong label name should be IAE")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing label name: label");

        metric.observe(longSupplier, "label", "long");
        metric.observe(doubleSupplier, "label", "double");

        assertThatThrownBy(() -> metric.observe(longSupplier, "label", "long"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A measurement with the same label values already exists");
        assertThatThrownBy(() -> metric.observe(doubleSupplier, "label", "double"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A measurement with the same label values already exists");
    }

    @Nested
    class BuilderTests extends MetricBaseTest<ObservableGauge, ObservableGauge.Builder>.BuilderTests {

        @Test
        void testNullSupplierThrows() {
            ObservableGauge.Builder builder = emptyMetricBuilder();

            assertThatThrownBy(() -> builder.observe((LongSupplier) null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("value supplier must not be null");

            assertThatThrownBy(() -> builder.observe((DoubleSupplier) null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("value supplier must not be null");
        }

        @Test
        void testNullLabelNamesAndValuesThrows() {
            ObservableGauge.Builder builder = emptyMetricBuilder();
            String[] nullLabelNamesAndValues = null;

            assertThatThrownBy(() -> builder.observe(() -> 42L, nullLabelNamesAndValues))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("Label names and values must not be null");

            assertThatThrownBy(() -> builder.observe(() -> 3.14, nullLabelNamesAndValues))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("Label names and values must not be null");
        }
    }

    @Nested
    class SnapshotTests extends MetricBaseTest<ObservableGauge, ObservableGauge.Builder>.SnapshotTests {

        @Test
        void testConcurrentRegistrations() throws InterruptedException {
            ObservableGauge metric =
                    emptyMetricBuilder().addDynamicLabelNames("thread").buildMetric();

            final int threadCount = 10;
            final int registrationsPerThread = 1000;

            ThreadUtils.runConcurrentAndWait(threadCount, Duration.ofSeconds(1), threadIdx -> () -> {
                for (int i = 0; i < registrationsPerThread; i++) {
                    int value = threadIdx * registrationsPerThread + i;

                    if (threadIdx % 2 == 0) {
                        metric.observe(() -> (long) value, "thread", "t" + value);
                    } else {
                        metric.observe(() -> (double) value, "thread", "t" + value);
                    }
                }
            });

            MetricSnapshotVerifier snapshotVerifier = new MetricSnapshotVerifier(metric).snapshotsAnyOrder();
            for (int i = 0; i < threadCount; i++) {
                for (int j = 0; j < registrationsPerThread; j++) {
                    int value = i * registrationsPerThread + j;

                    if (i % 2 == 0) {
                        snapshotVerifier.add((long) value, "thread", "t" + value);
                    } else {
                        snapshotVerifier.add((double) value, "thread", "t" + value);
                    }
                }
            }
            snapshotVerifier.verify();

            // verify reset is no-op for observable gauge
            resetMetric(metric);
            snapshotVerifier.verify();
        }
    }
}
