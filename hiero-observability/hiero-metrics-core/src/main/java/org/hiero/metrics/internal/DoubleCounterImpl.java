// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.function.DoubleSupplier;
import org.hiero.metrics.api.DoubleCounter;
import org.hiero.metrics.api.datapoint.DoubleCounterDataPoint;
import org.hiero.metrics.internal.core.AbstractStatefulMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.datapoint.DataPointHolder;
import org.hiero.metrics.internal.export.snapshot.DoubleValueDataPointSnapshotImpl;

public final class DoubleCounterImpl
        extends AbstractStatefulMetric<DoubleSupplier, DoubleCounterDataPoint, DoubleValueDataPointSnapshotImpl>
        implements DoubleCounter {

    public DoubleCounterImpl(DoubleCounter.Builder builder) {
        super(builder);
    }

    @Override
    protected void reset(DoubleCounterDataPoint dataPoint) {
        dataPoint.reset();
    }

    @Override
    protected DoubleValueDataPointSnapshotImpl createDataPointSnapshot(
            DoubleCounterDataPoint datapoint, LabelValues dynamicLabelValues) {
        return new DoubleValueDataPointSnapshotImpl(dynamicLabelValues);
    }

    @Override
    protected void updateDatapointSnapshot(
            DataPointHolder<DoubleCounterDataPoint, DoubleValueDataPointSnapshotImpl> dataPointHolder) {
        dataPointHolder.snapshot().set(dataPointHolder.dataPoint().getAsDouble());
    }
}
