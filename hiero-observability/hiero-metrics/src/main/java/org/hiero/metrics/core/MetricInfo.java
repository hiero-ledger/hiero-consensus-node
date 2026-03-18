// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

/**
 * Read-only information about a metric, including its type, name, unit, description, and associated labels.
 */
public interface MetricInfo {

    /**
     * @return the type of this metric, never {@code null}
     */
    @NonNull
    MetricType type();

    /**
     * @return the name of this metric, never {@code null}
     */
    @NonNull
    String name();

    /**
     * @return the unit of this metric, may be {@code null}
     */
    @Nullable
    String unit();

    /**
     * @return the description of this metric, may be {@code null}
     */
    @Nullable
    String description();

    /**
     * @return the immutable alphabetically ordered list of static labels associated with this metric
     * and any of its measurement, may be empty but never {@code null}
     */
    @NonNull
    List<Label> staticLabels();

    /**
     * @return the immutable alphabetically ordered list of dynamic label names associated with this metric,
     * may be empty but never {@code null}.
     * It is used to distinguish different measurements associated with this metric.
     */
    @NonNull
    List<String> dynamicLabelNames();
}
