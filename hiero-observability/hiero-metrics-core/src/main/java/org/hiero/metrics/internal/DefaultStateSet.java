// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.List;
import org.hiero.metrics.api.StateSet;
import org.hiero.metrics.api.datapoint.StateSetDataPoint;
import org.hiero.metrics.internal.core.AbstractStatefulMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.datapoint.DataPointHolder;
import org.hiero.metrics.internal.export.snapshot.DefaultStateSetDataPointSnapshot;

public class DefaultStateSet<E extends Enum<E>>
        extends AbstractStatefulMetric<List<E>, StateSetDataPoint<E>, DefaultStateSetDataPointSnapshot<E>>
        implements StateSet<E> {

    private final E[] enumConstants;

    public DefaultStateSet(StateSet.Builder<E> builder) {
        super(builder);
        enumConstants = builder.getEnumClass().getEnumConstants();
    }

    @Override
    protected DefaultStateSetDataPointSnapshot<E> createDataPointSnapshot(LabelValues dynamicLabelValues) {
        return new DefaultStateSetDataPointSnapshot<>(dynamicLabelValues, enumConstants);
    }

    @Override
    protected void updateDatapointSnapshot(
            DataPointHolder<StateSetDataPoint<E>, DefaultStateSetDataPointSnapshot<E>> dataPointHolder) {
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
