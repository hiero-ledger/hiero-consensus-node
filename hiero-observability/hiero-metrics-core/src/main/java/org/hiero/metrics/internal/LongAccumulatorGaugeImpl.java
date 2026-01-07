// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;
import org.hiero.metrics.api.LongAccumulatorGauge;
import org.hiero.metrics.api.export.snapshot.MeasurementSnapshot;
import org.hiero.metrics.api.measurement.LongAccumulatorGaugeMeasurement;
import org.hiero.metrics.internal.core.AbstractSettableMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.export.snapshot.LongValueMeasurementSnapshotImpl;
import org.hiero.metrics.internal.measurement.LongAccumulatorGaugeMeasurementImpl;

public class LongAccumulatorGaugeImpl
        extends AbstractSettableMetric<
                LongSupplier, LongAccumulatorGaugeMeasurement, LongAccumulatorGaugeMeasurementImpl>
        implements LongAccumulatorGauge {

    private final ToLongFunction<LongAccumulatorGaugeMeasurementImpl> exportValueSupplier;

    public LongAccumulatorGaugeImpl(LongAccumulatorGauge.Builder builder) {
        super(builder, init -> new LongAccumulatorGaugeMeasurementImpl(builder.getOperator(), init));

        exportValueSupplier = builder.isResetOnExport()
                ? LongAccumulatorGaugeMeasurementImpl::getAndReset
                : LongAccumulatorGaugeMeasurementImpl::get;
    }

    @Override
    protected LongValueMeasurementSnapshotImpl createMeasurementSnapshot(
            LongAccumulatorGaugeMeasurementImpl measurement, LabelValues dynamicLabelValues) {
        return new LongValueMeasurementSnapshotImpl(dynamicLabelValues);
    }

    @Override
    protected void updateMeasurementSnapshot(
            LongAccumulatorGaugeMeasurementImpl measurement, MeasurementSnapshot snapshot) {
        ((LongValueMeasurementSnapshotImpl) snapshot).set(exportValueSupplier.applyAsLong(measurement));
    }

    @Override
    protected void reset(LongAccumulatorGaugeMeasurementImpl measurement) {
        measurement.reset();
    }
}
