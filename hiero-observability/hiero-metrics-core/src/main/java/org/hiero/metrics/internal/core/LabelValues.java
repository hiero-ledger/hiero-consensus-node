// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;

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
                if (!Objects.equals(get(i), that.get(i))) {
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
            int limitValue = (Integer.MAX_VALUE >> 9);
            for (int i = 0; i < size(); i++) {
                hashCode = hashCode % limitValue; // avoid integer overflow
                hashCode = 257 * hashCode + Objects.hashCode(get(i));
            }
        }

        return hashCode;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(size() * 16);
        sb.append("LabelsValues[");
        for (int i = 0; i < size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(get(i));
        }
        return sb.append(']').toString();
    }
}
