// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;
import org.hiero.metrics.api.core.Label;
import org.hiero.metrics.api.core.Metric;
import org.hiero.metrics.api.core.MetricMetadata;
import org.hiero.metrics.api.export.snapshot.DataPointSnapshot;
import org.hiero.metrics.internal.datapoint.DataPointHolder;
import org.hiero.metrics.internal.export.SnapshotableMetric;
import org.hiero.metrics.internal.export.snapshot.UpdatableMetricSnapshot;

/**
 * Base class for all metric implementations requiring {@link Metric.Builder} for construction.
 * <p>
 * Implements common functionality like storing metadata, constant and dynamic labels, and managing
 * datapoint snapshots.<br>
 * Constant and dynamic labels are alphabetically sorted to ensure consistent ordering.
 * <p>
 * Subclasses must implement methods to create and update datapoint snapshots.
 * Snapshot objects are reused during export to minimize object allocations.
 *
 * @param <D> The type of the data point associated with this metric.
 * @param <S> The type of the {@link DataPointSnapshot} associated with this metric.
 */
public abstract class AbstractMetric<D, S extends DataPointSnapshot> implements SnapshotableMetric<S> {

    private final MetricMetadata metadata;
    private final List<Label> constantLabels;
    private final List<String> dynamicLabelNames;

    private final UpdatableMetricSnapshot<D, S> metricSnapshot;

    protected AbstractMetric(Builder<?, ?> builder) {
        metadata =
                new MetricMetadata(builder.type(), builder.key().name(), builder.getDescription(), builder.getUnit());

        constantLabels = builder.getConstantLabels().stream().sorted().toList();
        dynamicLabelNames = builder.getDynamicLabelNames().stream().sorted().toList();
        metricSnapshot = new UpdatableMetricSnapshot<>(this, this::updateDatapointSnapshot);
    }

    protected final DataPointHolder<D, S> createAndTrackDataPointHolder(D datapoint, LabelValues dynamicLabelValues) {
        DataPointHolder<D, S> dataPointHolder =
                new DataPointHolder<>(datapoint, createDataPointSnapshot(datapoint, dynamicLabelValues));
        metricSnapshot.addDataPointHolder(dataPointHolder);
        return dataPointHolder;
    }

    protected abstract S createDataPointSnapshot(D datapoint, LabelValues dynamicLabelValues);

    protected abstract void updateDatapointSnapshot(DataPointHolder<D, S> dataPointHolder);

    protected LabelValues createLabelValues(String... namesAndValues) {
        Objects.requireNonNull(namesAndValues, "Label names and values must not be null");

        if (namesAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("Label names and values must be in pairs");
        }

        final List<String> labelNames = dynamicLabelNames();

        if (namesAndValues.length / 2 != labelNames.size()) {
            throw new IllegalArgumentException(
                    "Expected " + labelNames.size() + " label names and values, got " + namesAndValues.length / 2);
        }

        if (labelNames.isEmpty()) {
            return LabelValues.empty();
        }

        // Defensive copy to avoid external modifications; cheap for few elements as typical use case fo labels
        final String[] nv = namesAndValues.clone();

        // sort names and values according to labelNames order
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

        return new LabelNamesAndValues(nv);
    }

    @NonNull
    public final MetricMetadata metadata() {
        return metadata;
    }

    @NonNull
    @Override
    public final List<Label> constantLabels() {
        return constantLabels;
    }

    @NonNull
    @Override
    public final List<String> dynamicLabelNames() {
        return dynamicLabelNames;
    }

    @Override
    public final UpdatableMetricSnapshot<D, S> snapshot() {
        return metricSnapshot;
    }
}
