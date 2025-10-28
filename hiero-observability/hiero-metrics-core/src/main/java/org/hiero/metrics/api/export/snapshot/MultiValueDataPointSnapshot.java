// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export.snapshot;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A {@link DataPointSnapshot} that contains multiple {@code double} or {@code long} values,
 * each identified by a type as string.
 * {@link #isFloatingPointAt(int)} can be used to determine the type of the value at specific index to avoid losing precision.
 * Value types are classified by a common string classifier accessible via {@link #valueClassifier()}, e.g. "stat".
 */
public interface MultiValueDataPointSnapshot extends DataPointSnapshot {

    /**
     * Classifier of the value types, e.g. "stat".
     *
     * @return value classifier
     */
    @NonNull
    String valueClassifier();

    /**
     * Value type at the given index.
     *
     * @param idx index in range [0, {@link #valuesCount()}).
     * @return value type at the given index e.g. "min", "max", "avg", "p95", etc.
     */
    @NonNull
    String valueTypeAt(int idx);

    /**
     * @return number of values of the data point.
     */
    int valuesCount();

    /**
     * @return {@code true} if all value at provided index is {@code double}, {@code false} if {@code long}.
     */
    boolean isFloatingPointAt(int idx);

    /**
     * Get {@code double} value at the given index.<br>
     * {@link #isFloatingPointAt(int)} has to be checked before calling this method or {@link #longValueAt(int)}.
     * Default implementations uses {@link Double#longBitsToDouble(long)} to convert {@code long} to {@code double}.
     *
     * @param idx index in range [0, {@link #valuesCount()}).
     * @return value at the given index
     */
    default double doubleValueAt(int idx) {
        return Double.longBitsToDouble(longValueAt(idx));
    }

    /**
     * Get {@code long} value at the given index.<br>
     * {@link #isFloatingPointAt(int)} has to be checked before calling this method or {@link #doubleValueAt(int)}.
     *
     * @param idx index in range [0, {@link #valuesCount()}).
     * @return value at the given index
     */
    long longValueAt(int idx);
}
