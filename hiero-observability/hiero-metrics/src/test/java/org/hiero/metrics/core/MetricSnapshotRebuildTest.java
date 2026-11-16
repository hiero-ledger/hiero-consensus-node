// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.hiero.metrics.LongCounter;
import org.hiero.metrics.LongGauge;
import org.hiero.metrics.ObservableGauge;
import org.junit.jupiter.api.Test;

class MetricSnapshotRebuildTest {

    @Test
    void testRegistrySnapshotKeepsLastOfMetricsWithEqualNames() {
        Metric<?> removed = LongCounter.builder("metric").build();
        Metric<?> other = LongCounter.builder("other").build();
        Metric<?> registeredAgain = LongGauge.builder("metric").build();
        MetricRegistrySnapshot snapshot = new MetricRegistrySnapshot(List.of(removed, other, registeredAgain));

        snapshot.update();

        assertThat(snapshot).containsExactly(registeredAgain.snapshot(), other.snapshot());
    }

    @Test
    void testRegistrySnapshotIsRebuiltOnlyAfterChanges() {
        AtomicInteger iterations = new AtomicInteger();
        List<Metric<?>> metrics = List.of(LongCounter.builder("metric").build());
        Collection<Metric<?>> counted = new AbstractCollection<>() {
            @Override
            public Iterator<Metric<?>> iterator() {
                iterations.incrementAndGet();
                return metrics.iterator();
            }

            @Override
            public int size() {
                return metrics.size();
            }
        };
        MetricRegistrySnapshot snapshot = new MetricRegistrySnapshot(counted);

        snapshot.update();
        snapshot.update();
        assertThat(iterations).as("rebuilt only by the first update").hasValue(1);

        snapshot.markChanged();
        snapshot.update();
        snapshot.update();
        assertThat(iterations).as("rebuilt once after the change").hasValue(2);
    }

    @Test
    void testRebuiltMeasurementsArePublishedAfterValuesAreUpdated() {
        ObservableGauge gauge =
                ObservableGauge.builder("gauge").addDynamicLabelNames("label").build();
        MetricSnapshot snapshot = snapshotOf(gauge);
        List<Long> readDuringUpdate = new ArrayList<>();
        LongSupplier reading = () -> {
            snapshot.forEach(measurement -> readDuringUpdate.add(((LongMeasurementSnapshot) measurement).get()));
            return 1L;
        };
        gauge.observe(reading, "label", "a");
        gauge.observe(() -> 2L, "label", "b");

        snapshot.update();

        assertThat(readDuringUpdate)
                .as("the rebuilt list is not iterated before its values are updated")
                .isEmpty();
        assertThat(values(snapshot)).containsExactly(1L, 2L);
    }

    @Test
    void testRebuiltMeasurementsArePublishedWhenValueUpdateFails() {
        AtomicBoolean failing = new AtomicBoolean(true);
        ObservableGauge gauge =
                ObservableGauge.builder("gauge").addDynamicLabelNames("label").build();
        MetricSnapshot snapshot = snapshotOf(gauge);
        gauge.observe(failingOnce(failing, 1L), "label", "a");
        gauge.observe(() -> 2L, "label", "b");

        assertThatThrownBy(snapshot::update).hasMessage("supplier failed");
        failing.set(false);
        snapshot.update();

        assertThat(values(snapshot))
                .as("the rebuild of the failed update is exported, although nothing changed after it")
                .containsExactly(1L, 2L);
    }

    @Test
    void testRebuiltMetricsArePublishedAfterValuesAreUpdated() {
        List<MetricSnapshot> readDuringUpdate = new ArrayList<>();
        AtomicReference<MetricRegistrySnapshot> registrySnapshot = new AtomicReference<>();
        ObservableGauge first = ObservableGauge.builder("first")
                .observe((LongSupplier) () -> {
                    registrySnapshot.get().forEach(readDuringUpdate::add);
                    return 1L;
                })
                .build();
        ObservableGauge second = ObservableGauge.builder("second").build();
        registrySnapshot.set(new MetricRegistrySnapshot(List.of(first, second)));

        registrySnapshot.get().update();

        assertThat(readDuringUpdate)
                .as("the rebuilt list is not iterated before its values are updated")
                .isEmpty();
        assertThat(registrySnapshot.get()).containsExactly(snapshotOf(first), snapshotOf(second));
    }

    @Test
    void testRebuiltMetricsArePublishedWhenValueUpdateFails() {
        AtomicBoolean failing = new AtomicBoolean(true);
        ObservableGauge first = ObservableGauge.builder("first")
                .observe(failingOnce(failing, 1L))
                .build();
        ObservableGauge second = ObservableGauge.builder("second").build();
        MetricRegistrySnapshot registrySnapshot = new MetricRegistrySnapshot(List.of(first, second));

        assertThatThrownBy(registrySnapshot::update).hasMessage("supplier failed");
        failing.set(false);
        registrySnapshot.update();

        assertThat(registrySnapshot)
                .as("the rebuild of the failed update is exported, although nothing changed after it")
                .containsExactly(snapshotOf(first), snapshotOf(second));
        assertThat(values(snapshotOf(first))).containsExactly(1L);
    }

    private static LongSupplier failingOnce(AtomicBoolean failing, long value) {
        return () -> {
            if (failing.get()) {
                throw new IllegalStateException("supplier failed");
            }
            return value;
        };
    }

    // Metric#snapshot() is package private, so it is not inherited by metrics in other packages
    private static MetricSnapshot snapshotOf(Metric<?> metric) {
        return metric.snapshot();
    }

    private static List<Long> values(MetricSnapshot snapshot) {
        List<Long> values = new ArrayList<>();
        snapshot.forEach(measurement -> values.add(((LongMeasurementSnapshot) measurement).get()));
        return values;
    }
}
