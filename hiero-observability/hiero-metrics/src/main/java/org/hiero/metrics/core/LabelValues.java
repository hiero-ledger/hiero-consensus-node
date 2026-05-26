// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;

/**
 * Represents a set of label values for a specific combination of dynamic labels.
 * <p>
 * {@link #equals(Object)} and {@link #hashCode()} methods are overridden to take only label values into account.
 * Names are not included because this class is constructed by metric when provided label names match those of the metric,
 * so they are not relevant for equality and hash code.
 */
public final class LabelValues {

    static final LabelValues EMPTY = new LabelValues();

    private final String[] namesAndValues;

    private final int hashCode;

    /**
     * Create label values instance with where values are provided together with label names as single array of pairs.
     */
    LabelValues(String... namesAndValues) {
        this.namesAndValues = namesAndValues;
        this.hashCode = computeHashCode();
    }

    /**
     * @return number of label values (is equal to number of dynamic labels of the metric it belongs to)
     */
    public int size() {
        return namesAndValues.length / 2;
    }

    /**
     * Get the label value at the specified index.
     *
     * @param index the index of the label value to retrieve
     * @return the label value at the specified index
     */
    @NonNull
    public String get(int index) {
        return namesAndValues[2 * index + 1];
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof LabelValues that) {
            if (size() != that.size()) {
                return false;
            }

            for (int i = 0; i < size(); i++) {
                if (!Objects.equals(get(i), that.get(i))) {
                    return false;
                }
            }

            return true;
        }

        return false;
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(size() * 16);
        sb.append('[');
        for (int i = 0; i < size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(get(i));
        }
        return sb.append(']').toString();
    }

    private int computeHashCode() {
        int h = 1;
        for (int i = 0; i < size(); i++) {
            h = 31 * h + Objects.hashCode(get(i));
        }
        return h;
    }
}
