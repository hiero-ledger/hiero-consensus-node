// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.function.DoubleSupplier;
import java.util.function.ToDoubleFunction;
import org.hiero.metrics.api.DoubleGauge;
import org.hiero.metrics.api.datapoint.DoubleGaugeDataPoint;
import org.hiero.metrics.internal.core.AbstractStatefulMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.datapoint.DataPointHolder;
import org.hiero.metrics.internal.export.snapshot.DefaultSingleValueDataPointSnapshot;

public final class DefaultDoubleGauge
        extends AbstractStatefulMetric<DoubleSupplier, DoubleGaugeDataPoint, DefaultSingleValueDataPointSnapshot>
        implements DoubleGauge {

    private final ToDoubleFunction<DoubleGaugeDataPoint> exportValueSupplier;

    public DefaultDoubleGauge(DoubleGauge.Builder builder) {
        super(builder);

        exportValueSupplier =
                builder.isResetOnExport() ? DoubleGaugeDataPoint::getAndReset : DoubleGaugeDataPoint::getAsDouble;
    }

    @Override
    protected DefaultSingleValueDataPointSnapshot createDataPointSnapshot(LabelValues dynamicLabelValues) {
        return new DefaultSingleValueDataPointSnapshot(dynamicLabelValues);
    }

    @Override
    protected void updateDatapointSnapshot(
            DataPointHolder<DoubleGaugeDataPoint, DefaultSingleValueDataPointSnapshot> dataPointHolder) {
        double value = exportValueSupplier.applyAsDouble(dataPointHolder.dataPoint());
        dataPointHolder.snapshot().update(value);
    }

    @Override
    protected void reset(DoubleGaugeDataPoint dataPoint) {
        dataPoint.reset();
    }
}
