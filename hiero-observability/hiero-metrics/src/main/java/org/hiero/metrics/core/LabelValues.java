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

    /**
     * Get the value of the label with the specified name.
     * Useful in filters, for example {@code labelValues -> "3".equals(labelValues.get("node"))}.
     * Throws for an unknown label name, so a misspelled name in a filter fails instead of matching nothing.
     *
     * @param labelName the name of the label to retrieve the value of, must not be {@code null}
     * @return the label value
     * @throws NullPointerException     if the label name is {@code null}
     * @throws IllegalArgumentException if there is no label with the specified name
     */
    @NonNull
    public String get(@NonNull String labelName) {
        Objects.requireNonNull(labelName, "label name must not be null");
        for (int i = 0; i < namesAndValues.length; i += 2) {
            if (namesAndValues[i].equals(labelName)) {
                return namesAndValues[i + 1];
            }
        }
        throw new IllegalArgumentException("Unknown label name: " + labelName);
    }

    /**
     * Compares label values of the same metric, value by value in the sorted order of the dynamic label names,
     * comparing values as strings, so {@code "10"} comes before {@code "2"}.
     * The result is meaningful only for label values of the same metric, which have the same label names,
     * because label names are not compared.
     * <p>
     * Package private, because it is used only to sort measurement snapshots of a metric snapshot.
     * This class is not {@link Comparable} on purpose: a natural ordering would make this string order public API,
     * so changing it later, for example to order numeric values by number, would break users, and it would invite
     * sorting label values of different metrics, for which this order has no meaning.
     */
    static int compare(@NonNull LabelValues first, @NonNull LabelValues second) {
        final int size = Math.min(first.size(), second.size());
        for (int i = 0; i < size; i++) {
            final int result = first.get(i).compareTo(second.get(i));
            if (result != 0) {
                return result;
            }
        }
        return Integer.compare(first.size(), second.size());
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
