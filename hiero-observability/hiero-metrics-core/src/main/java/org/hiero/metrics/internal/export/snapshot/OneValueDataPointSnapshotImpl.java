// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export.snapshot;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.metrics.api.export.snapshot.OneValueDataPointSnapshot;
import org.hiero.metrics.internal.core.LabelValues;

public class OneValueDataPointSnapshotImpl extends BaseDataPointSnapshot implements OneValueDataPointSnapshot {

    private final boolean isFloatingPoint;
    private long value;

    public OneValueDataPointSnapshotImpl(@NonNull LabelValues dynamicLabelValues, boolean isFloatingPoint) {
        super(dynamicLabelValues);
        this.isFloatingPoint = isFloatingPoint;
    }

    @Override
    public boolean isFloatingPoint() {
        return isFloatingPoint;
    }

    @Override
    public long getAsLong() {
        return value;
    }

    public void set(long value) {
        this.value = value;
    }

    public void set(double value) {
        this.value = Double.doubleToRawLongBits(value);
    }
}
