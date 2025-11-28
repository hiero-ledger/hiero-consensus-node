// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.hiero.metrics.api.StatelessMetric;
import org.hiero.metrics.api.core.NumberSupplier;
import org.hiero.metrics.api.export.snapshot.SingleValueDataPointSnapshot;
import org.hiero.metrics.internal.core.AbstractMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.datapoint.DataPointHolder;
import org.hiero.metrics.internal.export.snapshot.DoubleValueDataPointSnapshotImpl;
import org.hiero.metrics.internal.export.snapshot.LongValueDataPointSnapshotImpl;

public final class StatelessMetricImpl extends AbstractMetric<NumberSupplier, SingleValueDataPointSnapshot>
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
    protected SingleValueDataPointSnapshot createDataPointSnapshot(
            NumberSupplier datapoint, LabelValues dynamicLabelValues) {
        if (datapoint.isFloatingSupplier()) {
            return new DoubleValueDataPointSnapshotImpl(dynamicLabelValues);
        } else {
            return new LongValueDataPointSnapshotImpl(dynamicLabelValues);
        }
    }

    @Override
    protected void updateDatapointSnapshot(
            DataPointHolder<NumberSupplier, SingleValueDataPointSnapshot> dataPointHolder) {
        NumberSupplier datapoint = dataPointHolder.dataPoint();
        if (datapoint.isFloatingSupplier()) {
            ((DoubleValueDataPointSnapshotImpl) dataPointHolder.snapshot())
                    .set(datapoint.getDoubleSupplier().getAsDouble());
        } else {
            ((LongValueDataPointSnapshotImpl) dataPointHolder.snapshot())
                    .set(datapoint.getLongSupplier().getAsLong());
        }
    }

    @NonNull
    @Override
    public StatelessMetric registerDataPoint(
            @NonNull NumberSupplier valueSupplier, @NonNull String... labelNamesAndValues) {
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
