// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.metrics.internal.core.MetricUtils;

/**
 * A label is an immutable key-value pair that can be associated with a metric to provide additional context or
 * metadata. Labels are often used to differentiate between different instances of the same metric (measurements),
 * such as tracking the number of requests to a web server by different HTTP methods (e.g., GET,
 * POST, etc.) or by different response status codes (e.g., 200, 404, 500, etc.).
 * <p>
 * Label names must not be blank and must only contain valid characters defined by {@value MetricInfo#UNIT_LABEL_NAME_REGEX}.
 *
 * @see org.hiero.metrics.api.measurement.Measurement
 */
public record Label(@NonNull String name, @NonNull String value) implements Comparable<Label> {

    /**
     * Constructs a new label with the specified name and value.
     *
     * @param name  the name of the label, must not be blank
     * @param value the value of the label, must not be blank
     * @throws NullPointerException if name or value is {@code null}
     * @throws IllegalArgumentException if value is blank or name doesn't match regex {@value MetricInfo#UNIT_LABEL_NAME_REGEX}
     */
    public Label {
        MetricUtils.validateLabelNameCharacters(name);
        MetricUtils.throwArgBlank(value, "labelValue");
    }

    @Override
    public int compareTo(Label other) {
        int nameCompare = name.compareTo(other.name);
        return nameCompare != 0 ? nameCompare : value.compareTo(other.value);
    }
}
