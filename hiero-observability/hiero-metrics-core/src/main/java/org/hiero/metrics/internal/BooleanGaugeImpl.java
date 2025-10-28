// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.function.BooleanSupplier;
import org.hiero.metrics.api.BooleanGauge;
import org.hiero.metrics.api.datapoint.BooleanGaugeDataPoint;
import org.hiero.metrics.internal.core.AbstractStatefulMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.datapoint.DataPointHolder;
import org.hiero.metrics.internal.export.snapshot.LongValueDataPointSnapshotImpl;

public final class BooleanGaugeImpl
        extends AbstractStatefulMetric<BooleanSupplier, BooleanGaugeDataPoint, LongValueDataPointSnapshotImpl>
        implements BooleanGauge {

    public BooleanGaugeImpl(BooleanGauge.Builder builder) {
        super(builder);
    }

    @Override
    protected void reset(BooleanGaugeDataPoint dataPoint) {
        dataPoint.reset();
    }

    @Override
    protected LongValueDataPointSnapshotImpl createDataPointSnapshot(
            BooleanGaugeDataPoint datapoint, LabelValues dynamicLabelValues) {
        return new LongValueDataPointSnapshotImpl(dynamicLabelValues);
    }

    @Override
    protected void updateDatapointSnapshot(
            DataPointHolder<BooleanGaugeDataPoint, LongValueDataPointSnapshotImpl> dataPointHolder) {
        dataPointHolder.snapshot().set(dataPointHolder.dataPoint().getAsLong());
    }
}
