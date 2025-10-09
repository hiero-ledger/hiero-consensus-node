// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export.snapshot;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import org.hiero.metrics.api.export.snapshot.GenericMultiValueDataPointSnapshot;
import org.hiero.metrics.internal.core.LabelValues;

/**
 * Default implementation of {@link GenericMultiValueDataPointSnapshot}.
 */
public final class DefaultGenericMultiValueDataPointSnapshot extends BaseDataPointSnapshot
        implements GenericMultiValueDataPointSnapshot {

    private final String valueClassifier;
    private final String[] valueTypes;
    private final double[] values;

    public DefaultGenericMultiValueDataPointSnapshot(
            @NonNull LabelValues dynamicLabelValues, @NonNull String valueClassifier, @NonNull String[] valueTypes) {
        super(dynamicLabelValues);
        this.valueClassifier = valueClassifier;
        this.valueTypes = valueTypes;
        values = new double[valueTypes.length];
        Arrays.fill(values, Double.NaN);
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
    public double valueAt(int idx) {
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
    public void updateValueAt(int idx, double value) {
        values[idx] = value;
    }
}
