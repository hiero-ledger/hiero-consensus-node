// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import java.util.Set;
import org.hiero.metrics.api.StateSet;
import org.hiero.metrics.api.measurement.StateSetMeasurement;
import org.hiero.metrics.internal.core.AbstractSettableMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.export.snapshot.StateSetMeasurementSnapshotImpl;
import org.hiero.metrics.internal.measurement.MeasurementHolder;

public final class StateSetImpl<E extends Enum<E>>
        extends AbstractSettableMetric<Set<E>, StateSetMeasurement<E>, StateSetMeasurementSnapshotImpl<E>>
        implements StateSet<E> {

    private final E[] enumConstants;

    public StateSetImpl(StateSet.Builder<E> builder) {
        super(builder);
        enumConstants = builder.getEnumClass().getEnumConstants();
    }

    @Override
    protected StateSetMeasurementSnapshotImpl<E> createMeasurementSnapshot(
            StateSetMeasurement<E> measurement, LabelValues dynamicLabelValues) {
        return new StateSetMeasurementSnapshotImpl<>(dynamicLabelValues, enumConstants);
    }

    @Override
    protected void updateMeasurementSnapshot(
            MeasurementHolder<StateSetMeasurement<E>, StateSetMeasurementSnapshotImpl<E>> measurementHolder) {
        for (E enumConstant : enumConstants) {
            boolean value = measurementHolder.measurement().getState(enumConstant);
            measurementHolder.snapshot().updateState(enumConstant.ordinal(), value);
        }
    }

    @Override
    protected void reset(StateSetMeasurement<E> measurement) {
        measurement.reset();
    }
}
