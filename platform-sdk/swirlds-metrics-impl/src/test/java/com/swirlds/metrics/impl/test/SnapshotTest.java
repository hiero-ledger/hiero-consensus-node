// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.impl.test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.snapshot.Snapshot;
import com.swirlds.metrics.api.snapshot.SnapshotableMetric;
import java.util.List;
import org.junit.jupiter.api.Test;

class SnapshotTest {

    @Test
    void testToString() {
        // given
        final SnapshotableMetric metric = mock(SnapshotableMetric.class);
        when(metric.takeSnapshot()).thenReturn(List.of(new Snapshot.SnapshotEntry(Metric.ValueType.VALUE, 42L)));
        final Snapshot snapshot = Snapshot.of(metric);

        // when
        final String result = snapshot.toString();

        // then
        assertTrue(result.contains("valueType=VALUE"));
        assertTrue(result.contains("value=42"));
    }
}
