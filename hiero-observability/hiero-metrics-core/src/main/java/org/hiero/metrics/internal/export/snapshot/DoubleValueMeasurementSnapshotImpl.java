// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export.snapshot;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.metrics.api.export.snapshot.DoubleValueMeasurementSnapshot;
import org.hiero.metrics.internal.core.LabelValues;

public final class DoubleValueMeasurementSnapshotImpl extends BaseMeasurementSnapshot
        implements DoubleValueMeasurementSnapshot {

    private double value;

    public DoubleValueMeasurementSnapshotImpl(@NonNull LabelValues dynamicLabelValues) {
        super(dynamicLabelValues);
    }

    public void set(double value) {
        this.value = value;
    }

    @Override
    public double getAsDouble() {
        return value;
    }
}
