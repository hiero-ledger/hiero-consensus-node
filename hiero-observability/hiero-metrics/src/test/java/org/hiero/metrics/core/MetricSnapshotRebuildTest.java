// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.hiero.metrics.LongCounter;
import org.hiero.metrics.LongGauge;
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
}
