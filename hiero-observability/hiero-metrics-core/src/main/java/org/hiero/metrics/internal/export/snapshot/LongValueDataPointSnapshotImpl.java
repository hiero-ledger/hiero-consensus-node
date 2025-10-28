// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export.snapshot;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.metrics.api.export.snapshot.LongValueDataPointSnapshot;
import org.hiero.metrics.internal.core.LabelValues;

public final class LongValueDataPointSnapshotImpl extends BaseDataPointSnapshot implements LongValueDataPointSnapshot {

    private long value;

    public LongValueDataPointSnapshotImpl(@NonNull LabelValues dynamicLabelValues) {
        super(dynamicLabelValues);
    }

    public void set(long value) {
        this.value = value;
    }

    @Override
    public long getAsLong() {
        return value;
    }
}
