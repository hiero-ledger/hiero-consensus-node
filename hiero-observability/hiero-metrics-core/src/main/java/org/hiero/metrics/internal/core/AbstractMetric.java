// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Objects;
import org.hiero.metrics.api.core.Label;
import org.hiero.metrics.api.core.Metric;
import org.hiero.metrics.api.core.MetricType;
import org.hiero.metrics.api.export.snapshot.MeasurementSnapshot;
import org.hiero.metrics.internal.export.snapshot.MeasurementAndSnapshot;
import org.hiero.metrics.internal.export.snapshot.UpdatableMetricSnapshot;

/**
 * Base class for all metric implementations requiring {@link Metric.Builder} for construction.
 * <p>
 * Implements common functionality like storing metadata, static and dynamic labels, and managing
 * measurement snapshots.<br>
 * Static and dynamic labels are alphabetically sorted to ensure consistent ordering.
 * <p>
 * Subclasses must implement methods to create and update measurement snapshots.
 * Snapshot objects are reused during export to minimize object allocations.
 *
 * @param <M> The type of the measurement associated with this metric.
 */
public abstract class AbstractMetric<M> implements Metric {

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

    private final UpdatableMetricSnapshot<M> snapshot;

    protected AbstractMetric(Builder<?, ?> builder) {
        type = builder.type();
        name = builder.key().name();
        unit = builder.getUnit();
        description = builder.getDescription();

        staticLabels = builder.getStaticLabels().stream().sorted().toList();
        dynamicLabelNames = builder.getDynamicLabelNames().stream().sorted().toList();

        snapshot = new UpdatableMetricSnapshot<>(this);
    }

    public final UpdatableMetricSnapshot<M> snapshot() {
        return snapshot;
    }

    protected final void addMeasurementSnapshot(M measurement, LabelValues dynamicLabelValues) {
        snapshot.addMeasurementAndSnapshot(new MeasurementAndSnapshot<>(
                measurement,
                createMeasurementSnapshot(measurement, dynamicLabelValues),
                this::updateMeasurementSnapshot));
    }

    protected abstract MeasurementSnapshot createMeasurementSnapshot(M measurement, LabelValues dynamicLabelValues);

    protected abstract void updateMeasurementSnapshot(M measurement, MeasurementSnapshot snapshot);

    protected LabelValues createLabelValues(String... namesAndValues) {
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

        // Defensive copy to avoid external modifications; cheap for few elements as typical use case fo labels
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

    @Override
    @NonNull
    public final MetricType type() {
        return type;
    }

    @Override
    @NonNull
    public final String name() {
        return name;
    }

    @Override
    @Nullable
    public final String unit() {
        return unit;
    }

    @Override
    @Nullable
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
    public String toString() {
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
}
