// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.function.LongSupplier;
import org.hiero.metrics.api.LongCounter;
import org.hiero.metrics.api.datapoint.LongCounterDataPoint;
import org.hiero.metrics.internal.core.AbstractStatefulMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.datapoint.DataPointHolder;
import org.hiero.metrics.internal.export.snapshot.LongValueDataPointSnapshotImpl;

public final class LongCounterImpl
        extends AbstractStatefulMetric<LongSupplier, LongCounterDataPoint, LongValueDataPointSnapshotImpl>
        implements LongCounter {

    public LongCounterImpl(LongCounter.Builder builder) {
        super(builder);
    }

    @Override
    protected void reset(LongCounterDataPoint dataPoint) {
        dataPoint.reset();
    }

    @Override
    protected LongValueDataPointSnapshotImpl createDataPointSnapshot(
            LongCounterDataPoint datapoint, LabelValues dynamicLabelValues) {
        return new LongValueDataPointSnapshotImpl(dynamicLabelValues);
    }

    @Override
    protected void updateDatapointSnapshot(
            DataPointHolder<LongCounterDataPoint, LongValueDataPointSnapshotImpl> dataPointHolder) {
        dataPointHolder.snapshot().set(dataPointHolder.dataPoint().getAsLong());
    }
}
