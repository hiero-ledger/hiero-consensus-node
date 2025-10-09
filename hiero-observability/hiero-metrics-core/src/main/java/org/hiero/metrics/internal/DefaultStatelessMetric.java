// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;
import org.hiero.metrics.api.StatelessMetric;
import org.hiero.metrics.internal.core.AbstractMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.datapoint.DataPointHolder;
import org.hiero.metrics.internal.export.snapshot.DefaultSingleValueDataPointSnapshot;

public final class DefaultStatelessMetric extends AbstractMetric<DoubleSupplier, DefaultSingleValueDataPointSnapshot>
        implements StatelessMetric {

    private final Set<LabelValues> labelValuesSet = ConcurrentHashMap.newKeySet();

    public DefaultStatelessMetric(StatelessMetric.Builder builder) {
        super(builder);

        int dataPointsSize = builder.getDataPointsSize();
        for (int i = 0; i < dataPointsSize; i++) {
            registerDataPoint(builder.getValuesSupplier(i), builder.getDataPointsLabelNamesAndValues(i));
        }
    }

    @Override
    protected DefaultSingleValueDataPointSnapshot createDataPointSnapshot(LabelValues dynamicLabelValues) {
        return new DefaultSingleValueDataPointSnapshot(dynamicLabelValues);
    }

    @Override
    protected void updateDatapointSnapshot(
            DataPointHolder<DoubleSupplier, DefaultSingleValueDataPointSnapshot> dataPointHolder) {
        dataPointHolder.snapshot().update(dataPointHolder.dataPoint().getAsDouble());
    }

    @NonNull
    @Override
    public StatelessMetric registerDataPoint(
            @NonNull DoubleSupplier valueSupplier, @NonNull String... labelNamesAndValues) {
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
