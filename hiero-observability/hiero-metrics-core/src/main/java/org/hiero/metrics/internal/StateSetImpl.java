// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.Set;
import org.hiero.metrics.api.StateSet;
import org.hiero.metrics.api.datapoint.StateSetDataPoint;
import org.hiero.metrics.internal.core.AbstractStatefulMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.datapoint.DataPointHolder;
import org.hiero.metrics.internal.export.snapshot.StateSetDataPointSnapshotImpl;

public final class StateSetImpl<E extends Enum<E>>
        extends AbstractStatefulMetric<Set<E>, StateSetDataPoint<E>, StateSetDataPointSnapshotImpl<E>>
        implements StateSet<E> {

    private final E[] enumConstants;

    public StateSetImpl(StateSet.Builder<E> builder) {
        super(builder);
        enumConstants = builder.getEnumClass().getEnumConstants();
    }

    @Override
    protected StateSetDataPointSnapshotImpl<E> createDataPointSnapshot(
            StateSetDataPoint<E> datapoint, LabelValues dynamicLabelValues) {
        return new StateSetDataPointSnapshotImpl<>(dynamicLabelValues, enumConstants);
    }

    @Override
    protected void updateDatapointSnapshot(
            DataPointHolder<StateSetDataPoint<E>, StateSetDataPointSnapshotImpl<E>> dataPointHolder) {
        for (E enumConstant : enumConstants) {
            boolean value = dataPointHolder.dataPoint().getState(enumConstant);
            dataPointHolder.snapshot().updateState(enumConstant.ordinal(), value);
        }
    }

    @Override
    protected void reset(StateSetDataPoint<E> dataPoint) {
        dataPoint.reset();
    }
}
