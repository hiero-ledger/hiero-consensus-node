// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.core;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Represents a set of label values for a specific combination of dynamic labels.
 */
public abstract sealed class LabelValues permits LabelNamesAndValues, EmptyLabelValues {

    private int hashCode = 0;

    public static LabelValues empty() {
        return EmptyLabelValues.INSTANCE;
    }

    public abstract int size();

    @NonNull
    public abstract String get(int index);

    @Override
    public final boolean equals(Object other) {
        if (other instanceof LabelValues that) {
            if (size() != that.size()) {
                return false;
            }

            for (int i = 0; i < size(); i++) {
                if (!get(i).equals(that.get(i))) {
                    return false;
                }
            }

            return true;
        }

        return false;
    }

    @Override
    public final int hashCode() {
        if (hashCode == 0) {
            hashCode = 1;
            for (int i = 0; i < size(); i++) {
                hashCode = 31 * hashCode + get(i).hashCode();
            }
        }

        return hashCode;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(size() * 16);
        sb.append("Labels[");
        for (int i = 0; i < size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(get(i));
        }
        return sb.append(']').toString();
    }
}
