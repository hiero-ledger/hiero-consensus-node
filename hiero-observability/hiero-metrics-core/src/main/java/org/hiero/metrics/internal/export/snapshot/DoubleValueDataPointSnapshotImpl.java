// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export.snapshot;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.metrics.api.export.snapshot.DoubleValueDataPointSnapshot;
import org.hiero.metrics.internal.core.LabelValues;

public final class DoubleValueDataPointSnapshotImpl extends BaseDataPointSnapshot
        implements DoubleValueDataPointSnapshot {

    private double value;

    public DoubleValueDataPointSnapshotImpl(@NonNull LabelValues dynamicLabelValues) {
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
