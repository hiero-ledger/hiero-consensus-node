// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export.snapshot;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.metrics.api.export.snapshot.MultiValueDataPointSnapshot;
import org.hiero.metrics.internal.core.LabelValues;

/**
 * Default implementation of {@link MultiValueDataPointSnapshot} for {@code long} values.
 */
public final class MultiValueDataPointSnapshotImpl extends BaseDataPointSnapshot
        implements MultiValueDataPointSnapshot {

    private final String valueClassifier;
    private final String[] valueTypes;
    private final boolean[] isFloatingPointAt;
    private final long[] values;

    public MultiValueDataPointSnapshotImpl(
            @NonNull LabelValues dynamicLabelValues,
            @NonNull String valueClassifier,
            @NonNull String[] valueTypes,
            @NonNull boolean[] isFloatingPointAt) {
        super(dynamicLabelValues);
        this.valueClassifier = valueClassifier;
        this.valueTypes = valueTypes;
        this.isFloatingPointAt = isFloatingPointAt;
        values = new long[valueTypes.length];
    }

    @NonNull
    @Override
    public String valueClassifier() {
        return valueClassifier;
    }

    @Override
    public int valuesCount() {
        return values.length;
    }

    @Override
    public boolean isFloatingPointAt(int idx) {
        return isFloatingPointAt[idx];
    }

    @Override
    public long longValueAt(int idx) {
        return values[idx];
    }

    @NonNull
    @Override
    public String valueTypeAt(int idx) {
        return valueTypes[idx];
    }

    /**
     * Sets the value at the specified index.
     *
     * @param idx the index of the value to set
     * @param value the value to set
     */
    public void setValueAt(int idx, long value) {
        values[idx] = value;
    }

    /**
     * Sets the value at the specified index.
     *
     * @param idx the index of the value to set
     * @param value the value to set
     */
    public void setValueAt(int idx, double value) {
        values[idx] = Double.doubleToLongBits(value);
    }
}
