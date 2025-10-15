// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.hiero.metrics.api.StatelessMetric;
import org.hiero.metrics.api.core.LongOrDoubleSupplier;
import org.hiero.metrics.internal.core.AbstractMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.datapoint.DataPointHolder;
import org.hiero.metrics.internal.export.snapshot.OneValueDataPointSnapshotImpl;

public final class StatelessMetricImpl extends AbstractMetric<LongOrDoubleSupplier, OneValueDataPointSnapshotImpl>
        implements StatelessMetric {

    private final Set<LabelValues> labelValuesSet = ConcurrentHashMap.newKeySet();

    public StatelessMetricImpl(StatelessMetric.Builder builder) {
        super(builder);

        int dataPointsSize = builder.getDataPointsSize();
        for (int i = 0; i < dataPointsSize; i++) {
            registerDataPoint(builder.getValuesSupplier(i), builder.getDataPointsLabelNamesAndValues(i));
        }
    }

    @Override
    protected OneValueDataPointSnapshotImpl createDataPointSnapshot(
            LongOrDoubleSupplier datapoint, LabelValues dynamicLabelValues) {
        return new OneValueDataPointSnapshotImpl(dynamicLabelValues, datapoint.isDoubleSupplier());
    }

    @Override
    protected void updateDatapointSnapshot(
            DataPointHolder<LongOrDoubleSupplier, OneValueDataPointSnapshotImpl> dataPointHolder) {
        LongOrDoubleSupplier datapoint = dataPointHolder.dataPoint();
        if (datapoint.isDoubleSupplier()) {
            dataPointHolder.snapshot().set(datapoint.getDoubleValueSupplier().getAsDouble());
        } else {
            dataPointHolder.snapshot().set(datapoint.getLongValueSupplier().getAsLong());
        }
    }

    @NonNull
    @Override
    public StatelessMetric registerDataPoint(
            @NonNull LongOrDoubleSupplier valueSupplier, @NonNull String... labelNamesAndValues) {
        Objects.requireNonNull(valueSupplier, "Value supplier must not be null");

        LabelValues labelValues = createLabelValues(labelNamesAndValues);
        if (!labelValuesSet.add(labelValues)) {
            throw new IllegalArgumentException(
                    "A data point with the same label values already exists: " + labelValues);
        }

        createAndTrackDataPointHolder(valueSupplier, labelValues);
        return this;
    }
}
