// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;
import org.hiero.metrics.api.LongGauge;
import org.hiero.metrics.api.datapoint.LongGaugeDataPoint;
import org.hiero.metrics.internal.core.AbstractStatefulMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.datapoint.DataPointHolder;
import org.hiero.metrics.internal.export.snapshot.DefaultSingleValueDataPointSnapshot;

public final class DefaultLongGauge
        extends AbstractStatefulMetric<LongSupplier, LongGaugeDataPoint, DefaultSingleValueDataPointSnapshot>
        implements LongGauge {

    private final ToLongFunction<LongGaugeDataPoint> exportValueSupplier;

    public DefaultLongGauge(LongGauge.Builder builder) {
        super(builder);

        exportValueSupplier =
                builder.isResetOnExport() ? LongGaugeDataPoint::getAndReset : LongGaugeDataPoint::getAsLong;
    }

    @Override
    protected DefaultSingleValueDataPointSnapshot createDataPointSnapshot(LabelValues dynamicLabelValues) {
        return new DefaultSingleValueDataPointSnapshot(dynamicLabelValues);
    }

    @Override
    protected void updateDatapointSnapshot(
            DataPointHolder<LongGaugeDataPoint, DefaultSingleValueDataPointSnapshot> dataPointHolder) {
        long value = exportValueSupplier.applyAsLong(dataPointHolder.dataPoint());
        double doubleValue = value;

        if (Long.MAX_VALUE == value) {
            doubleValue = Double.POSITIVE_INFINITY;
        } else if (Long.MIN_VALUE == value) {
            doubleValue = Double.NEGATIVE_INFINITY;
        }

        dataPointHolder.snapshot().update(doubleValue);
    }

    @Override
    protected void reset(LongGaugeDataPoint dataPoint) {
        dataPoint.reset();
    }
}
