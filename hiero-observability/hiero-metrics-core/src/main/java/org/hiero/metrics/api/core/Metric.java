// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.hiero.metrics.api.utils.MetricUtils;
import org.hiero.metrics.api.utils.Unit;

/**
 * Base interface for all metrics.
 * <p>
 * A metric is defined by its {@link MetricMetadata}, a {@link Label} set of
 * static labels and a set of dynamic label names (may have no labels at all). Metric cannot have duplicate
 * label names, and dynamic label names must not conflict with static label names. Labels are used to differentiate
 * different data points associated with the metric.
 * <p>
 * Metrics are immutable and thread-safe, but they may hold mutable {@link org.hiero.metrics.api.datapoint.DataPoint}s
 * per dynamic labels set, that can be updated with new measurements.
 * Metrics can also be reset to their initial state, which resets all associated datapoints.
 * <p>
 * Since metric can support aggregations (like sum, min, max, avg, etc.), this interface doesn't expose
 * export or snapshot functionality - {@link org.hiero.metrics.api.export.MetricsExportManager} should be used
 * to export metrics to different destinations.
 */
public interface Metric {

    /**
     * @return the immutable metadata associated with this metric, never {@code null}
     */
    @NonNull
    MetricMetadata metadata();

    /**
     * @return the immutable alphabetically ordered list of static labels associated with this metric
     * and any of its data point, may be empty but never {@code null}
     */
    @NonNull
    List<Label> staticLabels();

    /**
     * @return the immutable alphabetically ordered list of dynamic label names associated with this metric,
     * may be empty but never {@code null}
     */
    @NonNull
    List<String> dynamicLabelNames();

    /**
     * Allows to reset the metric and all it's datapoints to its initial state.
     */
    void reset();

    /**
     * Base abstract builder for all metric types.
     * <p>
     * Requires {@link MetricType} and {@link MetricKey} to be specified at construction time, and provides
     * methods to set optional fields: description, unit, static labels and dynamic label names.
     * Dynamic label names must be unique and must not conflict with static label names. Exception will be thrown at
     * metric build time if there are conflicts.
     * <p>
     * Builder is mutable so must not be reused for building multiple metric instances.
     *
     * @param <B> the concrete builder type
     * @param <M> the concrete metric type
     */
    abstract class Builder<B extends Builder<B, M>, M extends Metric> {

        private final MetricType type;
        private final MetricKey<M> key;
        private String description;
        private String unit;

        protected final Map<String, Label> staticLabels = new HashMap<>();
        private final Set<String> dynamicLabelNames = new HashSet<>();

        /**
         * Constructor for a metric builder.
         *
         * @param type the metric type, must not be {@code null}
         * @param key  the metric key, must not be {@code null}
         */
        protected Builder(@NonNull MetricType type, @NonNull MetricKey<M> key) {
            this.type = Objects.requireNonNull(type, "type must not be null");
            this.key = Objects.requireNonNull(key, "key must not be null");
        }

        /**
         * @return the metric type, never {@code null}
         */
        @NonNull
        public final MetricType type() {
            return type;
        }

        /**
         * @return the metric key, never {@code null}
         */
        @NonNull
        public MetricKey<M> key() {
            return key;
        }

        /**
         * @return the metric description, {@code null} if not set
         */
        @Nullable
        public String getDescription() {
            return description;
        }

        /**
         * @return the metric unit, {@code null} if not set
         */
        @Nullable
        public String getUnit() {
            return unit;
        }

        /**
         * @return collection of static labels, never {@code null}, possibly empty
         */
        @NonNull
        public Collection<Label> getStaticLabels() {
            return staticLabels.values();
        }

        /**
         * @return dynamic label names as set, never {@code null}, possibly empty
         */
        @NonNull
        public Set<String> getDynamicLabelNames() {
            return dynamicLabelNames;
        }

        /**
         * Sets the metric description.
         *
         * @param description the metric description, may be {@code null}
         * @return the builder instance
         */
        @NonNull
        public final B withDescription(@Nullable String description) {
            this.description = description;
            return self();
        }

        /**
         * Sets the metric unit. <br>
         * Unit, if not null or empty, must only contain valid characters
         * - see {@link MetricUtils#validateNameCharacters(String)}.
         *
         * @param unit the metric unit, may be {@code null}
         * @return the builder instance
         */
        @NonNull
        public final B withUnit(@Nullable String unit) {
            if (unit != null && !unit.isBlank()) {
                this.unit = MetricUtils.validateNameCharacters(unit);
            } else {
                this.unit = null;
            }
            return self();
        }

        /**
         * Sets the metric unit. <br>
         *
         * @param unit the metric unit, must not be {@code null}
         * @return the builder instance
         */
        public final B withUnit(@NonNull Unit unit) {
            Objects.requireNonNull(unit, "unit must not be null");
            this.unit = unit.toString();
            return self();
        }

        /**
         * Removes any previously set unit from the metric.
         */
        protected final void withoutUnit() {
            this.unit = null;
        }

        /**
         * Adds dynamic label names to the metric. Dynamic label names must be unique and must not conflict with
         * static label names. Exception will be thrown at metric build time if there are conflicts. <br>
         * Label name must not be blank and must only contain valid characters
         *  - see {@link MetricUtils#validateNameCharacters(String)}.
         *
         * @param labelNames the dynamic label names to add, must not be {@code null}
         * @return the builder instance
         */
        @NonNull
        public final B withDynamicLabelNames(@NonNull String... labelNames) {
            Objects.requireNonNull(labelNames, "label names must not be null");
            for (String labelName : labelNames) {
                MetricUtils.validateNameCharacters(labelName);
                validateLabelNameNoEqualMetricName(labelName);
            }
            dynamicLabelNames.addAll(Arrays.asList(labelNames));
            return self();
        }

        /**
         * Adds a static label to the metric. Static label names must be unique and must not conflict with
         * dynamic label names. Exception will be thrown at metric build time if there are conflicts.
         *
         * @param label the static label to add, must not be {@code null}
         * @return the builder instance
         */
        @NonNull
        public final B withStaticLabel(@NonNull Label label) {
            Objects.requireNonNull(label, "label must not be null");
            validateLabelNameNoEqualMetricName(label.name());

            Label existingLabel = staticLabels.put(label.name(), label);
            if (existingLabel != null && !existingLabel.equals(label)) {
                throw new IllegalArgumentException(label + " conflicts with existing: " + existingLabel);
            }
            return self();
        }

        /**
         * Adds static labels to the metric. Static label names must be unique and must not conflict with
         * dynamic label names. Exception will be thrown at metric build time if there are conflicts.
         *
         * @param labels the static labels to add, must not be {@code null}
         * @return the builder instance
         */
        @NonNull
        public final B withStaticLabels(@NonNull Collection<Label> labels) {
            Objects.requireNonNull(labels, "labels must not be null");

            for (Label label : labels) {
                withStaticLabel(label);
            }
            return self();
        }

        /**
         * Adds static labels to the metric. Static label names must be unique and must not conflict with
         * dynamic label names. Exception will be thrown at metric build time if there are conflicts.
         *
         * @param labels the static labels to add, must not be {@code null}
         * @return the builder instance
         */
        @NonNull
        public final B withStaticLabels(@NonNull Label... labels) {
            Objects.requireNonNull(labels, "labels must not be null");
            return withStaticLabels(Arrays.asList(labels));
        }

        /**
         * Builds the metric instance. Validates that dynamic label names do not conflict with static label names.
         *
         * @return the built metric instance, never {@code null}
         * @throws IllegalStateException if there are conflicts between dynamic and static label names
         */
        @NonNull
        public final M build() {
            for (String dynamicLabelName : dynamicLabelNames) {
                Label constLabel = staticLabels.get(dynamicLabelName);
                if (constLabel != null) {
                    throw new IllegalStateException("Dynamic label name '" + dynamicLabelName
                            + "' conflicts with a static label: " + constLabel);
                }
            }
            return buildMetric();
        }

        /**
         * Registers the built metric instance with the provided metric registry.
         * {@link MetricRegistry} may perform additional changes to the builder before registering the metric.
         *
         * @param registry the metric registry to register with, must not be {@code null}
         * @return the registered metric instance, never {@code null}
         */
        @NonNull
        public final M register(@NonNull MetricRegistry registry) {
            Objects.requireNonNull(registry, "registry must not be null");
            return registry.register(this);
        }

        /**
         * Builds the metric instance. Subclasses must implement this method to create the specific metric type.
         *
         * @return the built metric instance, never {@code null}
         */
        @NonNull
        protected abstract M buildMetric();

        /**
         * @return the builder instance concrete type to support fluent API
         */
        @NonNull
        @SuppressWarnings("unchecked")
        protected final B self() {
            return (B) this;
        }

        private void validateLabelNameNoEqualMetricName(String labelName) {
            if (labelName.equals(key.name())) {
                throw new IllegalArgumentException("Label name must not be the same as metric name: " + labelName);
            }
        }
    }
}
