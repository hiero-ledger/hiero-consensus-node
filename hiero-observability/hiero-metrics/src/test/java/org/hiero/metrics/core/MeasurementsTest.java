// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.hiero.metrics.ThreadUtils;
import org.junit.jupiter.api.Test;

class MeasurementsTest {

    @Test
    void testComputeIfAbsentCreatesOnceAndIsExportedAfterUpdate() {
        TestMetric metric = TestMetric.builder("label").build();
        MetricSnapshot snapshot = metric.snapshot();
        snapshot.update();

        AtomicLong created = metric.measurements().computeIfAbsent(labels("a"), _ -> new AtomicLong(1));
        AtomicLong existing = metric.measurements().computeIfAbsent(labels("a"), _ -> new AtomicLong(2));

        assertThat(existing).isSameAs(created);
        assertThat(metric.measurements().get(labels("a"))).isSameAs(created);
        assertThat(metric.snapshotsCreated)
                .as("snapshots are created by exports")
                .hasValue(0);
        assertThat(exportedLabels(snapshot, false))
                .as("not exported before update")
                .isEmpty();
        assertThat(exportedLabels(snapshot, true)).containsExactly("a");
        assertThat(metric.snapshotsCreated)
                .as("created by the first export collecting it")
                .hasValue(1);

        metric.measurements().computeIfAbsent(labels("b"), _ -> new AtomicLong(3));
        assertThat(exportedLabels(snapshot, true)).containsExactly("a", "b");
        assertThat(metric.snapshotsCreated)
                .as("rebuild reuses the snapshot of a")
                .hasValue(2);
    }

    @Test
    void testComputeIfAbsentWithFailingFactoryAddsNoMeasurement() {
        TestMetric metric = TestMetric.builder("label").build();

        assertThatThrownBy(() -> metric.measurements().computeIfAbsent(labels("a"), _ -> {
                    throw new IllegalStateException("failed");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> metric.measurements().computeIfAbsent(labels("a"), _ -> null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("measurement must not be null");

        assertThat(metric.measurements().get(labels("a"))).isNull();
        assertThat(exportedLabels(metric.snapshot(), true)).isEmpty();
    }

    @Test
    void testComputeIfAbsentConcurrentlyCreatesOnceAndIsExported() throws InterruptedException {
        TestMetric metric = TestMetric.builder("label").build();
        MetricSnapshot snapshot = metric.snapshot();
        snapshot.update();

        int threadCount = 10;
        AtomicInteger factoryCalls = new AtomicInteger();
        AtomicLong[] results = new AtomicLong[threadCount];
        ThreadUtils.runConcurrentAndWait(
                threadCount,
                Duration.ofSeconds(1),
                threadIdx -> () -> results[threadIdx] = metric.measurements().computeIfAbsent(labels("a"), _ -> {
                    factoryCalls.incrementAndGet();
                    return new AtomicLong();
                }));

        assertThat(results).allSatisfy(result -> assertThat(result).isSameAs(results[0]));
        assertThat(factoryCalls).as("measurement created once").hasValue(1);
        // every thread that did not find the measurement marks the measurements, the creating one always among them
        assertThat(exportedLabels(snapshot, true)).containsExactly("a");
        assertThat(metric.snapshotsCreated)
                .as("snapshot created once, by the export")
                .hasValue(1);
    }

    @Test
    void testPutIfAbsentAddsOnlyFirstMeasurement() {
        TestMetric metric = TestMetric.builder("label").build();
        AtomicLong first = new AtomicLong(1);

        assertThat(metric.measurements().putIfAbsent(labels("a"), first)).isTrue();
        assertThat(metric.measurements().putIfAbsent(labels("a"), new AtomicLong(2)))
                .isFalse();

        assertThat(metric.measurements().get(labels("a"))).isSameAs(first);
        assertThat(exportedLabels(metric.snapshot(), true)).containsExactly("a");
        assertThat(metric.snapshotsCreated)
                .as("only the added measurement gets a snapshot, created by the export")
                .hasValue(1);
        assertThat(((LongMeasurementSnapshot) metric.snapshot().iterator().next()).get())
                .as("exported snapshot is the one of the added measurement")
                .isEqualTo(1);
    }

    @Test
    void testSnapshotsAreCollectedOnlyAfterChanges() {
        TestMetric metric = TestMetric.builder("label").build();
        Measurements<AtomicLong> measurements = metric.measurements();

        assertThat(measurements.snapshotsIfChanged())
                .as("collected by the first export")
                .isEmpty();
        assertThat(measurements.snapshotsIfChanged()).as("nothing changed").isNull();

        measurements.computeIfAbsent(labels("a"), _ -> new AtomicLong());
        assertThat(measurements.snapshotsIfChanged()).as("added").hasSize(1);

        measurements.computeIfAbsent(labels("a"), _ -> new AtomicLong());
        assertThat(measurements.putIfAbsent(labels("a"), new AtomicLong())).isFalse();
        assertThatThrownBy(() -> measurements.computeIfAbsent(labels("b"), _ -> {
                    throw new IllegalStateException("failed");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(metric.removeIf(_ -> false)).isZero();
        assertThat(measurements.snapshotsIfChanged())
                .as("lookups, failed additions and removing nothing do not change the measurements")
                .isNull();

        assertThat(metric.removeIf(_ -> true)).isEqualTo(1);
        assertThat(measurements.snapshotsIfChanged()).as("removed").isEmpty();
        assertThat(measurements.snapshotsIfChanged()).as("nothing changed").isNull();
    }

    @Test
    void testChangeAfterSnapshotsAreCollectedIsCollectedNextTime() {
        TestMetric metric = TestMetric.builder("label").build();
        Measurements<AtomicLong> measurements = metric.measurements();
        measurements.computeIfAbsent(labels("a"), _ -> new AtomicLong());

        // the export clears the flag before reading the measurements, so a change made after it is collected again
        assertThat(measurements.snapshotsIfChanged()).hasSize(1);
        measurements.computeIfAbsent(labels("b"), _ -> new AtomicLong());

        assertThat(measurements.snapshotsIfChanged()).hasSize(2);
    }

    @Test
    void testRemoveIfRemovesMatchingAndIsExportedAfterUpdate() {
        TestMetric metric = TestMetric.builder("label").build();
        metric.measurements().putIfAbsent(labels("a"), new AtomicLong());
        metric.measurements().putIfAbsent(labels("b"), new AtomicLong());
        MetricSnapshot snapshot = metric.snapshot();
        snapshot.update();

        assertThat(metric.removeIf(labelValues -> "a".equals(labelValues.get("label"))))
                .isEqualTo(1);

        assertThat(metric.measurements().get(labels("a"))).isNull();
        assertThat(exportedLabels(snapshot, false))
                .as("not exported before update")
                .containsExactly("a", "b");
        assertThat(exportedLabels(snapshot, true)).containsExactly("b");
    }

    @Test
    void testRemoveIfDoesNotRemoveMeasurementAddedAgain() {
        TestMetric metric = TestMetric.builder("label").build();
        metric.measurements().putIfAbsent(labels("a"), new AtomicLong(1));
        AtomicLong addedAgain = new AtomicLong(2);

        // the filter runs without a lock, so another removal and addition can happen in between
        int removed = metric.removeIf(_ -> {
            metric.removeIf(_ -> true);
            metric.measurements().putIfAbsent(labels("a"), addedAgain);
            return true;
        });

        assertThat(removed).isZero();
        assertThat(metric.measurements().get(labels("a"))).isSameAs(addedAgain);
        assertThat(exportedLabels(metric.snapshot(), true)).containsExactly("a");
    }

    @Test
    void testRemoveIfDoesNotRemoveEqualMeasurementAddedAgainBeforeExport() {
        // no export ran, so entries have no snapshots and differ only by their measurements, which are equal by value
        Measurements<ValueMeasurement> measurements = new Measurements<>((_, _) -> {
            throw new AssertionError("snapshots are created only by exports");
        });
        measurements.putIfAbsent(labels("a"), new ValueMeasurement(1));
        ValueMeasurement addedAgain = new ValueMeasurement(1);

        // the filter runs without a lock, so another removal and addition can happen in between
        int removed = measurements.removeIf(_ -> {
            measurements.removeIf(_ -> true);
            measurements.putIfAbsent(labels("a"), addedAgain);
            return true;
        });

        assertThat(removed)
                .as("entries must be compared by identity, not by their measurements")
                .isZero();
        assertThat(measurements.get(labels("a"))).isSameAs(addedAgain);
    }

    @Test
    void testFailedSnapshotCreationIsRetriedByNextExport() {
        AtomicInteger factoryCalls = new AtomicInteger();
        Measurements<AtomicLong> measurements = new Measurements<>((measurement, labelValues) -> {
            if (factoryCalls.incrementAndGet() == 1) {
                throw new IllegalStateException("snapshot creation failed");
            }
            return new LongMeasurementSnapshot(labelValues, measurement::get);
        });
        measurements.putIfAbsent(labels("a"), new AtomicLong());

        assertThatThrownBy(measurements::snapshotsIfChanged)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("snapshot creation failed");

        assertThat(measurements.snapshotsIfChanged())
                .as("the failed export leaves the measurements marked")
                .hasSize(1);
        assertThat(measurements.snapshotsIfChanged()).as("nothing changed").isNull();
    }

    @Test
    void testRemoveIfWithThrowingFilterExportsPartialRemoval() {
        TestMetric metric = TestMetric.builder("label").build();
        metric.measurements().putIfAbsent(labels("a"), new AtomicLong());
        metric.measurements().putIfAbsent(labels("b"), new AtomicLong());
        MetricSnapshot snapshot = metric.snapshot();
        snapshot.update();

        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> metric.removeIf(_ -> {
                    if (calls.incrementAndGet() == 2) {
                        throw new IllegalStateException("filter failed");
                    }
                    return true;
                }))
                .isInstanceOf(IllegalStateException.class);

        // the entry visited first was removed before the filter threw; map order is not known, so look it up
        List<String> remaining = Stream.of("a", "b")
                .filter(value -> metric.measurements().get(labels(value)) != null)
                .toList();
        assertThat(remaining).hasSize(1);
        assertThat(exportedLabels(snapshot, true))
                .as("the removal is exported although the filter threw")
                .containsExactlyElementsOf(remaining);
    }

    @Test
    void testRemoveIfThrowsWhenNoDynamicLabelsDefined() {
        TestMetric metric = TestMetric.builder().build();

        assertThatThrownBy(() -> metric.removeIf(_ -> true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("This metric has no dynamic labels, so it can be removed only from the metric registry");
    }

    @Test
    void testRemoveIfNullFilterThrows() {
        TestMetric metric = TestMetric.builder("label").build();

        assertThatThrownBy(() -> metric.removeIf(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("filter must not be null");
    }

    @Test
    void testForEachVisitsAllMeasurements() {
        TestMetric metric = TestMetric.builder("label").build();
        metric.measurements().putIfAbsent(labels("a"), new AtomicLong(1));
        metric.measurements().putIfAbsent(labels("b"), new AtomicLong(2));

        metric.reset();

        assertThat(metric.measurements().get(labels("a"))).hasValue(0);
        assertThat(metric.measurements().get(labels("b"))).hasValue(0);
    }

    @Test
    void testNotExportedMetricCreatesNoSnapshots() {
        TestMetric.Builder builder = TestMetric.builder("label");
        builder.doNotSnapshot();
        TestMetric metric = builder.build();

        metric.measurements().computeIfAbsent(labels("a"), _ -> new AtomicLong());
        metric.measurements().putIfAbsent(labels("b"), new AtomicLong());

        assertThat(metric.snapshot()).isNull();
        assertThat(metric.snapshotsCreated).hasValue(0);
        assertThat(metric.measurements().get(labels("a"))).isNotNull();
    }

    private record ValueMeasurement(long value) {}

    private static LabelValues labels(String value) {
        return new LabelValues("label", value);
    }

    private static List<String> exportedLabels(MetricSnapshot snapshot, boolean update) {
        if (update) {
            snapshot.update();
        }
        List<String> labels = new ArrayList<>();
        snapshot.forEach(
                measurement -> labels.add(measurement.getDynamicLabelValues().get(0)));
        return labels;
    }

    private static final class TestMetric extends Metric<AtomicLong> {

        private final AtomicInteger snapshotsCreated = new AtomicInteger();

        private TestMetric(Builder builder) {
            super(builder);
        }

        static Builder builder(String... dynamicLabelNames) {
            return new Builder().addDynamicLabelNames(dynamicLabelNames);
        }

        @Override
        protected @NonNull MeasurementSnapshot createMeasurementSnapshot(
                @NonNull AtomicLong measurement, @NonNull LabelValues labelValues) {
            snapshotsCreated.incrementAndGet();
            return new LongMeasurementSnapshot(labelValues, measurement::get);
        }

        @Override
        protected void reset() {
            measurements().forEach(measurement -> measurement.set(0));
        }

        private static final class Builder extends Metric.Builder<Builder, TestMetric> {

            private Builder() {
                super(MetricType.GAUGE, MetricKey.of("test_metric", TestMetric.class));
            }

            @Override
            protected TestMetric buildMetric() {
                return new TestMetric(this);
            }
        }
    }
}
