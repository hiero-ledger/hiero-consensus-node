// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.metrics.TestUtils.verifySnapshotHasMetrics;

import java.util.Iterator;
import java.util.NoSuchElementException;
import org.hiero.metrics.api.LongCounter;
import org.hiero.metrics.api.export.snapshot.MetricSnapshot;
import org.hiero.metrics.internal.core.MetricRegistryImpl;
import org.junit.jupiter.api.Test;

public class MetricsSnapshotImplTest {

    @Test
    void testEmptyIterator() {
        MetricsSnapshotImpl snapshots = new MetricsSnapshotImpl();
        Iterator<MetricSnapshot> iterator = snapshots.iterator();

        assertThat(iterator.hasNext()).isFalse();
        assertThatThrownBy(iterator::next).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void testSingleEmptyRegistryIterator() {
        MetricsSnapshotImpl snapshots = new MetricsSnapshotImpl();
        snapshots.addRegistry(new MetricRegistryImpl());

        Iterator<MetricSnapshot> iterator = snapshots.iterator();

        assertThat(iterator.hasNext()).isFalse();
        assertThatThrownBy(iterator::next).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void testMultipleEmptyRegistryIterator() {
        MetricsSnapshotImpl snapshots = new MetricsSnapshotImpl();
        snapshots.addRegistry(new MetricRegistryImpl());
        snapshots.addRegistry(new MetricRegistryImpl());

        Iterator<MetricSnapshot> iterator = snapshots.iterator();

        assertThat(iterator.hasNext()).isFalse();
        assertThatThrownBy(iterator::next).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void testSingleNonEmptyRegistryIterator() {
        MetricsSnapshotImpl snapshots = new MetricsSnapshotImpl();

        MetricRegistryImpl registry = new MetricRegistryImpl();
        registry.register(LongCounter.builder("counter1"));
        snapshots.addRegistry(registry);

        snapshots.update();
        verifySnapshotHasMetrics(snapshots, "counter1");
    }

    @Test
    void testMultipleNonEmptyRegistryIterator() {
        MetricsSnapshotImpl snapshots = new MetricsSnapshotImpl();

        MetricRegistryImpl registry1 = new MetricRegistryImpl();
        registry1.register(LongCounter.builder("counter1"));
        snapshots.addRegistry(registry1);

        MetricRegistryImpl registry2 = new MetricRegistryImpl();
        registry2.register(LongCounter.builder("counter2"));
        snapshots.addRegistry(registry2);

        snapshots.update();
        verifySnapshotHasMetrics(snapshots, "counter1", "counter2");
    }

    @Test
    void testMixOfEmptyAndNonEmptyRegistries() {
        MetricsSnapshotImpl snapshots = new MetricsSnapshotImpl();

        MetricRegistryImpl registry1 = new MetricRegistryImpl();
        registry1.register(LongCounter.builder("counter1"));
        snapshots.addRegistry(registry1);

        MetricRegistryImpl registry2 = new MetricRegistryImpl();
        snapshots.addRegistry(registry2);

        snapshots.update();
        verifySnapshotHasMetrics(snapshots, "counter1");

        MetricRegistryImpl registry3 = new MetricRegistryImpl();
        registry3.register(LongCounter.builder("counter3"));
        snapshots.addRegistry(registry3);

        snapshots.update();
        verifySnapshotHasMetrics(snapshots, "counter1", "counter3");
    }
}
