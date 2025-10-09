// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export.snapshot;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.metrics.api.export.snapshot.SingleValueDataPointSnapshot;
import org.hiero.metrics.internal.core.LabelValues;

/**
 * Default implementation of {@link SingleValueDataPointSnapshot}.
 */
public final class DefaultSingleValueDataPointSnapshot extends BaseDataPointSnapshot
        implements SingleValueDataPointSnapshot {

    private double value = Double.NaN;

    public DefaultSingleValueDataPointSnapshot(@NonNull LabelValues dynamicLabelValues) {
        super(dynamicLabelValues);
    }

    /**
     * Update the value of this snapshot.
     *
     * @param value the new value
     */
    public void update(double value) {
        this.value = value;
    }

    @Override
    public double getAsDouble() {
        return value;
    }
}
