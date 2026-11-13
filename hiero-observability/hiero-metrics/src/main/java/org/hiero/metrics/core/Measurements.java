// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Concurrent container of the measurements of a {@link Metric} by their dynamic label values.
 * <p>
 * The {@link Metric} constructor creates the container, and subclasses keep all their measurements in it,
 * accessing it by {@link Metric#measurements()}. Adding or removing measurements only changes the map and notifies
 * the metric, which marks its {@link MetricSnapshot}, if it is exported, so the next export rebuilds the measurement
 * snapshots from this container.
 * The export creates the snapshot of a measurement the first time it collects it, so application threads never
 * create snapshots or call the code reading measurement values, and a metric that is not exported has no snapshots.
 * A measurement of a metric with no dynamic labels is kept under empty label values.
 *
 * @param <M> the type of the measurements
 */
public final class Measurements<M> {

    private final ConcurrentHashMap<LabelValues, Entry<M>> entries = new ConcurrentHashMap<>();

    // called by any thread after measurements are added or removed
    private final Runnable onChange;

    /**
     * Package private, because it is created only by the {@link Metric} constructor.
     *
     * @param onChange called after measurements are added or removed, to mark the metric snapshot as changed
     */
    Measurements(@NonNull Runnable onChange) {
        this.onChange = Objects.requireNonNull(onChange, "on change callback must not be null");
    }

    /**
     * Returns the measurement with the given label values. Lock-free.
     *
     * @param labelValues the dynamic label values of the measurement, must not be {@code null}
     * @return the measurement, or {@code null} if there is none with the given label values
     */
    @Nullable
    public M get(@NonNull LabelValues labelValues) {
        final Entry<M> entry = entries.get(labelValues);
        return entry != null ? entry.measurement : null;
    }

    /**
     * Returns the measurement with the given label values, creating it with the given factory if there is none.
     * <p>
     * The lookup of an existing measurement is lock-free. A new measurement is created under the lock of the map bin,
     * so it is created once per label values, and a failed creation adds no measurement.
     *
     * @param labelValues the dynamic label values of the measurement, must not be {@code null}
     * @param factory     creates the measurement for the given label values, must not return {@code null}
     * @return the existing or created measurement, never {@code null}
     * @throws NullPointerException if the factory returns {@code null}
     */
    @NonNull
    public M computeIfAbsent(@NonNull LabelValues labelValues, @NonNull Function<LabelValues, ? extends M> factory) {
        // lock-free lookup first: computeIfAbsent may lock the map bin even when the key exists
        final Entry<M> existing = entries.get(labelValues);
        if (existing != null) {
            return existing.measurement;
        }
        final Entry<M> entry = entries.computeIfAbsent(labelValues, key -> new Entry<>(key, factory.apply(key)));
        // Marked after computeIfAbsent returns, when the measurement is visible to the export rebuilding the snapshot.
        // Also marked if another thread created the measurement concurrently, between the lookup above and
        // computeIfAbsent. That mark is redundant, because the creating thread marks after its own computeIfAbsent,
        // but harmless: the flag is a boolean, so it costs at most one extra rebuild, and only if an export runs
        // between the two marks. Marking only when this call created the measurement would need a flag set
        // inside the mapping function, for no difference in what is exported.
        markChanged();
        return entry.measurement;
    }

    /**
     * Adds the given measurement, if there is no measurement with the given label values.
     *
     * @param labelValues the dynamic label values of the measurement, must not be {@code null}
     * @param measurement the measurement to add, must not be {@code null}
     * @return {@code true} if the measurement was added, {@code false} if there is already a measurement
     * with the given label values
     */
    public boolean putIfAbsent(@NonNull LabelValues labelValues, @NonNull M measurement) {
        if (entries.putIfAbsent(labelValues, new Entry<>(labelValues, measurement)) != null) {
            return false;
        }
        // marked after putIfAbsent returns, when the measurement is visible to the export rebuilding the snapshot
        markChanged();
        return true;
    }

    /**
     * Performs the given action for each measurement.
     * Measurements added or removed concurrently may or may not be passed to the action.
     *
     * @param action the action to perform for each measurement, must not be {@code null}
     */
    public void forEach(@NonNull Consumer<? super M> action) {
        Objects.requireNonNull(action, "action must not be null");
        for (Entry<M> entry : entries.values()) {
            action.accept(entry.measurement);
        }
    }

    /**
     * Removes the measurements whose dynamic label values match the given filter.
     * Package private, because it is called only by {@link Metric#removeIf(Predicate)}, which documents the behavior.
     * <p>
     * Only the measurement the filter was called for is removed, not one added again for equal label values since.
     */
    int removeIf(@NonNull Predicate<LabelValues> filter) {
        Objects.requireNonNull(filter, "filter must not be null");

        int removed = 0;
        try {
            for (Map.Entry<LabelValues, Entry<M>> entry : entries.entrySet()) {
                if (filter.test(entry.getKey()) && entries.remove(entry.getKey(), entry.getValue())) {
                    removed++;
                }
            }
        } finally {
            // marked also if the filter throws, so measurements removed before are not exported anymore
            if (removed > 0) {
                markChanged();
            }
        }
        return removed;
    }

    /**
     * Collects the snapshots of the current measurements, creating the snapshots of measurements collected for the
     * first time with the given factory, and reusing them afterwards.
     * Package private, because it is called only by the {@link MetricSnapshot} of the metric when an export rebuilds
     * its list of measurement snapshots, one export at a time.
     * If creating a snapshot fails, the list stays marked, so the next export collects them again.
     *
     * @param snapshotFactory creates the snapshot of a measurement collected for the first time
     * @return the snapshots in iteration order of the map
     */
    @NonNull
    List<MeasurementSnapshot> collectSnapshots(
            @NonNull BiFunction<? super M, LabelValues, MeasurementSnapshot> snapshotFactory) {
        final List<MeasurementSnapshot> collected = new ArrayList<>(entries.size());
        for (Entry<M> entry : entries.values()) {
            collected.add(entry.snapshot(snapshotFactory));
        }
        return collected;
    }

    private void markChanged() {
        onChange.run();
    }

    /**
     * A measurement with its label values and its snapshot, which the first export collecting the entry creates.
     * <p>
     * Not a record: {@link ConcurrentHashMap#remove(Object, Object)} in {@link #removeIf(Predicate)} compares entries
     * with {@code equals}, which must stay identity based. A record would compare the measurements, so a stale removal
     * could remove a measurement with equal value added again for the same label values.
     */
    private static final class Entry<M> {
        private final LabelValues labelValues;
        private final M measurement;

        // Read and written only by exports, which MetricRegistrySnapshot.update() runs one at a time,
        // so it needs no synchronization of its own.
        @Nullable
        private MeasurementSnapshot snapshot;

        private Entry(LabelValues labelValues, M measurement) {
            this.labelValues = labelValues;
            this.measurement = Objects.requireNonNull(measurement, "measurement must not be null");
        }

        // lazy init snapshot or reuse the existing one, so a measurement that is not exported has no snapshot
        private MeasurementSnapshot snapshot(
                BiFunction<? super M, LabelValues, ? extends MeasurementSnapshot> snapshotFactory) {
            if (snapshot == null) {
                snapshot = Objects.requireNonNull(
                        snapshotFactory.apply(measurement, labelValues), "measurement snapshot must not be null");
            }
            return snapshot;
        }
    }
}
