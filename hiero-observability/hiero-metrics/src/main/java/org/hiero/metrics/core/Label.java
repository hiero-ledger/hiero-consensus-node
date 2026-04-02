// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A label is an immutable key-value pair used to differentiate measurements withing same metric.
 */
public record Label(@NonNull String name, @NonNull String value) implements Comparable<Label> {

    /**
     * Constructs a new label with the specified name and value.
     *
     * @param name  the name of the label, must not be blank
     * @param value the value of the label, must not be blank
     * @throws NullPointerException if name or value is {@code null}
     * @throws IllegalArgumentException if value is blank or name doesn't match regex {@value MetricUtils#UNIT_LABEL_NAME_REGEX}
     */
    public Label {
        MetricUtils.validateLabelNameCharacters(name);
        MetricUtils.throwArgBlank(value, "labelValue");
    }

    @Override
    public String toString() {
        return name + "=" + value;
    }

    @Override
    public int compareTo(Label other) {
        int nameCompare = name.compareTo(other.name);
        return nameCompare != 0 ? nameCompare : value.compareTo(other.value);
    }
}
