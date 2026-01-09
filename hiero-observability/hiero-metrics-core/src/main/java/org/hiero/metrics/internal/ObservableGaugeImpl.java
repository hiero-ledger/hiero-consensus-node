// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.hiero.metrics.api.ObservableGauge;
import org.hiero.metrics.api.core.NumberSupplier;
import org.hiero.metrics.api.export.snapshot.MeasurementSnapshot;
import org.hiero.metrics.internal.core.AbstractMetric;
import org.hiero.metrics.internal.core.LabelValues;
import org.hiero.metrics.internal.export.snapshot.DoubleValueMeasurementSnapshotImpl;
import org.hiero.metrics.internal.export.snapshot.LongValueMeasurementSnapshotImpl;

public final class ObservableGaugeImpl extends AbstractMetric<NumberSupplier> implements ObservableGauge {

    private final Set<LabelValues> labelValuesSet = ConcurrentHashMap.newKeySet();

    public ObservableGaugeImpl(ObservableGauge.Builder builder) {
        super(builder);

        final int size = builder.getObservedValuesSize();
        for (int i = 0; i < size; i++) {
            observeValue(builder.getValuesSupplier(i), builder.getObservedValuesLabelNamesAndValues(i));
        }
    }

    @Override
    public MeasurementSnapshot createSnapshot(NumberSupplier measurement, LabelValues dynamicLabelValues) {
        if (measurement.isFloatingSupplier()) {
            return new DoubleValueMeasurementSnapshotImpl(dynamicLabelValues);
        } else {
            return new LongValueMeasurementSnapshotImpl(dynamicLabelValues);
        }
    }

    @Override
    public void updateSnapshot(NumberSupplier measurement, MeasurementSnapshot snapshot) {
        if (measurement.isFloatingSupplier()) {
            ((DoubleValueMeasurementSnapshotImpl) snapshot)
                    .set(measurement.getDoubleSupplier().getAsDouble());
        } else {
            ((LongValueMeasurementSnapshotImpl) snapshot)
                    .set(measurement.getLongSupplier().getAsLong());
        }
    }

    @NonNull
    @Override
    public ObservableGauge observeValue(@NonNull NumberSupplier valueSupplier, @NonNull String... labelNamesAndValues) {
        Objects.requireNonNull(valueSupplier, "Value supplier must not be null");

        LabelValues labelValues = createLabelValues(labelNamesAndValues);
        if (!labelValuesSet.add(labelValues)) {
            throw new IllegalArgumentException(
                    "A measurement with the same label values already exists: " + labelValues);
        }

        snapshot().addMeasurement(valueSupplier, labelValues);
        return this;
    }
}
