// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle.metric;

import com.swirlds.common.metrics.platform.SnapshotEvent;
import com.swirlds.metrics.api.Metric.DataType;
import com.swirlds.metrics.api.snapshot.Snapshot;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.hiero.consensus.model.node.NodeId;

/**
 * A collector that stores snapshot values from metrics, organized by NodeId,
 * metric identifier, and ValueType.
 */
public class MetricsCollector {

    private final Map<Identifier, NumberStats> numericData = new ConcurrentHashMap<>();
    private final Map<Identifier, List<String>> stringData = new ConcurrentHashMap<>();
    private final Map<Identifier, List<Boolean>> booleanData = new ConcurrentHashMap<>();

    /**
     * Handle a snapshot event and store all associated metric values in memory.
     *
     * @param snapshotEvent the event containing snapshot data per metric
     */
    public void handleSnapshots(final SnapshotEvent snapshotEvent) {
        final NodeId nodeId = snapshotEvent.nodeId();
        final Collection<Snapshot> snapshots = snapshotEvent.snapshots();

        for (final Snapshot snapshot : snapshots) {
            final String metricId = snapshot.metric().getIdentifier();
            final DataType dataType = snapshot.metric().getDataType();
            final Object value = snapshot.getValue();
            final Identifier key = new Identifier(nodeId, metricId);

            switch (dataType) {
                case INT -> {
                    if (value instanceof Long number) {
                        numericData.computeIfAbsent(key, k -> new LongStats()).updateValue(number);
                    }
                }
                case FLOAT -> {
                    if (value instanceof Double number) {
                        numericData.computeIfAbsent(key, k -> new DoubleStats()).updateValue(number);
                    }
                }
                case STRING -> {
                    if (value instanceof String str) {
                        stringData.computeIfAbsent(key, k -> new ArrayList<>()).add(str);
                    }
                }
                case BOOLEAN -> {
                    if (value instanceof Boolean bool) {
                        booleanData.computeIfAbsent(key, k -> new ArrayList<>()).add(bool);
                    }
                }
            }
        }
    }

    public NumberStats getNumbers(@NonNull final NodeId nodeId, @NonNull final String metricId) {
        return numericData.get(new Identifier(nodeId, metricId));
    }

    record Identifier(@Nullable NodeId nodeId, @NonNull String metricId) {}
}
