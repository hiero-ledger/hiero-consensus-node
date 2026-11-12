// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Base class for all metric implementations.
 * <p>
 * It contains common to all metrics immutable metadata like name, type, unit, description, static and dynamic labels.
 * Static and dynamic labels are alphabetically sorted to ensure consistent ordering.
 * <p>
 * Constructor of this class requires a {@link Builder} instance to initialize common metric metadata.
 * Subclasses extending this class must provide their own builder extending {@link Builder}.
 * <p>
 * Additionally it keeps the measurements of the metric in a {@link Measurements} container, which subclasses access
 * by {@link #measurements()}, and a reusable {@link MetricSnapshot} instance for exporting them.
 * Subclasses, knowing their measurement and snapshot type, implement
 * {@link #createMeasurementSnapshot(Object, LabelValues)}, and the container keeps the measurement snapshots exported
 * by the metric snapshot in sync with the measurements.
 *
 * @param <M> the type of the measurements
 */
public abstract class Metric<M> implements MetricInfo {

    @NonNull
    private final MetricType type;

    @NonNull
    private final String name;

    @Nullable
    private final String unit;

    @Nullable
    private final String description;

    private final List<Label> staticLabels;
    private final List<String> dynamicLabelNames;

    private final Measurements<M> measurements;

    @Nullable
    private final MetricSnapshot snapshot;

    protected Metric(Builder<?, ?> builder) {
        type = builder.type;
        name = builder.key.name();
        unit = builder.unit;
        description = builder.description;

        staticLabels = builder.staticLabels.values().stream().sorted().toList();
        dynamicLabelNames = builder.dynamicLabelNames.stream().sorted().toList();

        // the snapshot factory is called only by exports, after this metric is constructed and registered
        measurements = new Measurements<>(this::createMeasurementSnapshot);
        if (builder.snapshotable) {
            // created after the measurements, which the metric snapshot reads from this metric
            snapshot = new MetricSnapshot(this);
        } else {
            snapshot = null;
        }
    }

    @NonNull
    @Override
    public final MetricType type() {
        return type;
    }

    @NonNull
    @Override
    public final String name() {
        return name;
    }

    @Nullable
    @Override
    public final String unit() {
        return unit;
    }

    @Nullable
    @Override
    public final String description() {
        return description;
    }

    @NonNull
    @Override
    public final List<Label> staticLabels() {
        return staticLabels;
    }

    @NonNull
    @Override
    public final List<String> dynamicLabelNames() {
        return dynamicLabelNames;
    }

    @Override
    public final String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append("type=").append(type);
        sb.append(", name='").append(name).append('\'');
        if (unit != null) {
            sb.append(", unit='").append(unit).append('\'');
        }
        if (description != null) {
            sb.append(", description='").append(description).append('\'');
        }
        sb.append(", staticLabels=").append(staticLabels);
        sb.append(", dynamicLabelNames=").append(dynamicLabelNames);

        return sb.toString();
    }

    /**
     * Returns the metric snapshot for exporting measurements.
     * <p>
     * This method is package private to avoid exposing it in the public API and only called from metric registry
     * when providing snapshot to registered exporter.
     */
    @Nullable
    final MetricSnapshot snapshot() {
        return snapshot;
    }

    /**
     * Resets all measurements associated with this metric to their initial state.
     * Subclasses must implement this method to reset their specific measurements.
     * <p>
     * This method is protected to avoid exposing it in the public API and only called when whole metric registry
     * is reset by calling {@link MetricRegistry#reset()}.
     */
    protected abstract void reset();

    /**
     * Removes all measurements whose dynamic label values match the given filter, so they are not exported anymore.
     * Use {@link LabelValues#get(String)} to match by label name, for example
     * {@code metric.removeIf(labelValues -> "3".equals(labelValues.get("node")))}.
     * <p>
     * The filter is called for the label values of each existing measurement without holding any lock.
     * Measurements added concurrently with this call may or may not be removed.
     * If the filter throws, measurements removed before it threw stay removed, and the exception is rethrown.
     * <p>
     * Removed measurements are released by the next export, which rebuilds the exported snapshot without them;
     * until then the snapshot of the last export still references them, and an export already in progress may read
     * a removed measurement once more. References to removed measurements held by callers remain usable,
     * but their updates are not exported anymore, so callers caching measurements must drop them on removal.
     * <p>
     * A metric without dynamic labels has a single measurement, which can be removed only together with the metric
     * by {@link MetricRegistry#remove(MetricKey)}.
     *
     * @param filter returns {@code true} for label values of measurements to remove, must not be {@code null}
     * @return the number of removed measurements
     * @throws IllegalStateException if metric has no dynamic labels specified during creation
     * @throws NullPointerException  if the filter is {@code null}
     */
    public final int removeIf(@NonNull Predicate<LabelValues> filter) {
        // the single measurement of a metric without labels is never removed: subclasses may cache it,
        // for example SettableMetric, whose cached reference would otherwise stop being exported
        if (dynamicLabelNames.isEmpty()) {
            throw new IllegalStateException(
                    "This metric has no dynamic labels, so it can be removed only from the metric registry");
        }
        return measurements.removeIf(filter);
    }

    /**
     * Creates the snapshot of the given measurement with its dynamic label values for export.
     * <p>
     * Called only if this metric is exported, once per measurement in {@link #measurements()}, by the first export
     * that collects the measurement. It is never called by the thread adding the measurement or under a lock of the
     * measurements map. The created snapshot must not read the measurement value: the export updates it right after.
     *
     * @param measurement the measurement to create the snapshot for
     * @param labelValues the dynamic label values of the measurement
     * @return the created snapshot, must not be {@code null}
     */
    @NonNull
    protected abstract MeasurementSnapshot createMeasurementSnapshot(
            @NonNull M measurement, @NonNull LabelValues labelValues);

    /**
     * Returns the container of the measurements of this metric, created by the constructor.
     * Subclasses must keep all their measurements in it, so they are exported.
     *
     * @return the measurements container, never {@code null}
     */
    @NonNull
    protected final Measurements<M> measurements() {
        return measurements;
    }

    /**
     * Creates {@link LabelValues} from the provided label names and values.
     * <p>
     * The provided names and values must be in pairs and match the dynamic label names defined for this metric.
     * The order of names and values does not matter, as they will be sorted according to the dynamic label names order.
     *
     * @param namesAndValues the label names and values in pairs
     * @return the created {@link LabelValues} instance
     * @throws NullPointerException     if {@code namesAndValues} is {@code null} or any label value is {@code null}
     * @throws IllegalArgumentException if the number of names and values is not even,
     *                                  or if the number of labels does not match the dynamic label names,
     *                                  or if any expected label name is missing
     */
    protected final LabelValues createLabelValues(@NonNull String... namesAndValues) {
        Objects.requireNonNull(namesAndValues, "Label names and values must not be null");

        if (namesAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("Label names and values must be in pairs");
        }

        final List<String> labelNames = dynamicLabelNames();

        if (namesAndValues.length / 2 != labelNames.size()) {
            throw new IllegalArgumentException(
                    "Expected " + labelNames.size() + " labels, got " + namesAndValues.length / 2);
        }

        if (labelNames.isEmpty()) {
            return LabelValues.EMPTY;
        }

        // Defensive copy to avoid external modifications; cheap for few elements as typical use case for labels
        final String[] nv = namesAndValues.clone();

        // sort names and values according to dynamic labelNames order
        for (int i = 0; i < labelNames.size(); i++) {
            String labelName = labelNames.get(i);

            int foundLabelIdx = 2 * i;
            while (foundLabelIdx < nv.length) {
                if (labelName.equals(nv[foundLabelIdx])) {
                    if (nv[foundLabelIdx + 1] == null) {
                        throw new NullPointerException("Label value must not be null for label: " + labelName);
                    }
                    break;
                }
                foundLabelIdx += 2;
            }

            if (foundLabelIdx >= nv.length) {
                throw new IllegalArgumentException("Missing label name: " + labelName);
            }

            // swap only if not already on its place
            if (foundLabelIdx > 2 * i) {
                String tmpName = nv[2 * i];
                String tmpValue = nv[2 * i + 1];
                nv[2 * i] = nv[foundLabelIdx];
                nv[2 * i + 1] = nv[foundLabelIdx + 1];
                nv[foundLabelIdx] = tmpName;
                nv[foundLabelIdx + 1] = tmpValue;
            }
        }

        return new LabelValues(nv);
    }

    /**
     * Base builder class for constructing {@link Metric} instances.
     * <b>Builders must never be reused.</b>
     *
     * @param <B> the concrete builder type to return for method chaining
     * @param <M> the concrete metric type to build
     */
    public abstract static class Builder<B extends Metric.Builder<B, M>, M extends Metric<?>> {

        private final MetricType type;
        private final MetricKey<M> key;
        private String description;
        private String unit;
        private boolean snapshotable = true;

        private final Map<String, Label> staticLabels = new HashMap<>();
        private final Set<String> dynamicLabelNames = new HashSet<>();

        /**
         * Constructor for a metric builder.
         *
         * @param type the metric type, must not be {@code null}
         * @param key  the metric key, must not be {@code null}
         * @throws NullPointerException if any of the parameters is {@code null}
         */
        protected Builder(@NonNull MetricType type, @NonNull MetricKey<M> key) {
            this.type = Objects.requireNonNull(type, "type must not be null");
            this.key = Objects.requireNonNull(key, "key must not be null");
        }

        /**
         * @return the metric key, never {@code null}
         */
        @NonNull
        public MetricKey<M> key() {
            return key;
        }

        /**
         * Sets the metric description.
         *
         * @param description the metric description, may be {@code null}
         * @return the builder instance
         */
        @NonNull
        public final B setDescription(@Nullable String description) {
            this.description = description;
            return self();
        }

        /**
         * Sets the metric unit. <br>
         * Blank or empty unit will be treated as no unit (set to {@code null}).
         *
         * @param unit the metric unit, may be {@code null}
         * @return the builder instance
         * @throws IllegalArgumentException if the unit is not null and doesn't match regex {@value MetricUtils#NAME_UNIT_LABEL_REGEX}
         */
        @NonNull
        public final B setUnit(@Nullable String unit) {
            if (unit != null && !unit.isBlank()) {
                this.unit = MetricUtils.validateUnitNameCharacters(unit);
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
         * @throws NullPointerException if the unit is {@code null}
         */
        public final B setUnit(@NonNull Unit unit) {
            Objects.requireNonNull(unit, "unit must not be null");
            this.unit = unit.toString();
            return self();
        }

        /**
         * Adds dynamic label names to the metric. <br>
         * Dynamic label names result to be unique (without duplicates) and must not conflict with
         * static label names or metric name. If duplicates are added, they will be ignored.
         * Exception will be thrown at metric build time, if there is static and dynamic labels with the same name.
         *
         * @param labelNames the dynamic label names to add, must not be {@code null}
         * @return the builder instance
         * @throws NullPointerException if any label name is {@code null}
         * @throws IllegalArgumentException if any label name doesn't match regex {@value MetricUtils#NAME_UNIT_LABEL_REGEX}
         */
        @NonNull
        public final B addDynamicLabelNames(@NonNull String... labelNames) {
            Objects.requireNonNull(labelNames, "label names must not be null");
            for (String labelName : labelNames) {
                MetricUtils.validateLabelNameCharacters(labelName);
                validateLabelNameNoEqualMetricName(labelName);
                dynamicLabelNames.add(labelName);
            }
            return self();
        }

        /**
         * Adds a static label to the metric. Static label names must be unique and must not conflict with
         * dynamic label names or metric name.
         * Exception will be thrown at metric build time, if there is static and dynamic labels with the same name.
         *
         * @param labels the static labels to add, must not be {@code null}
         * @return the builder instance
         * @throws NullPointerException if label is {@code null}
         * @throws IllegalArgumentException if label name doesn't match regex {@value MetricUtils#NAME_UNIT_LABEL_REGEX}
         */
        @NonNull
        public final B addStaticLabels(@NonNull Label... labels) {
            Objects.requireNonNull(labels, "labels must not be null");

            for (Label label : labels) {
                validateLabelNameNoEqualMetricName(label.name());

                Label existingLabel = staticLabels.put(label.name(), label);
                if (existingLabel != null && !existingLabel.equals(label)) {
                    throw new IllegalArgumentException(label + " conflicts with existing: " + existingLabel);
                }
            }

            return self();
        }

        final void doNotSnapshot() {
            this.snapshotable = false;
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
