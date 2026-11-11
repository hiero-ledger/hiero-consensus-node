// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Concurrent container of the measurements of a {@link Metric} by their dynamic label values,
 * which keeps the snapshots exported for the measurements in sync with them.
 * <p>
 * The {@link Metric} constructor creates the container, and subclasses keep all their measurements in it,
 * accessing it by {@link Metric#measurements()}. Adding a measurement creates its snapshot together with it,
 * if the metric is exported, and adding or removing measurements marks them as changed, so the next export rebuilds
 * the metric snapshot from this container. A measurement of a metric with no dynamic labels is kept under empty
 * label values.
 *
 * @param <M> the type of the measurements
 */
public final class Measurements<M> {

    private final ConcurrentHashMap<LabelValues, Entry<M>> entries = new ConcurrentHashMap<>();

    // false if the metric is not exported, so no snapshots are created
    private final boolean exported;

    private final BiFunction<? super M, LabelValues, ? extends MeasurementSnapshot> snapshotFactory;

    // set by any thread after measurements are added or removed, cleared by the export collecting the snapshots
    private final AtomicBoolean changed = new AtomicBoolean(true);

    /**
     * Package private, because it is created only by the {@link Metric} constructor.
     */
    Measurements(
            boolean exported,
            @NonNull BiFunction<? super M, LabelValues, ? extends MeasurementSnapshot> snapshotFactory) {
        this.exported = exported;
        this.snapshotFactory = Objects.requireNonNull(snapshotFactory, "snapshot factory must not be null");
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
     * The lookup of an existing measurement is lock-free. A new measurement and its snapshot are created under
     * the lock of the map bin, so they are created once per label values, and a failed creation adds no measurement.
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
        final Entry<M> entry = entries.computeIfAbsent(labelValues, key -> newEntry(key, factory.apply(key)));
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
     * <p>
     * The snapshot of the measurement is created before adding it, without holding any lock, so it is created
     * even if the measurement is not added. Callers creating snapshots that read values from user code
     * should check for an existing measurement with {@link #get(LabelValues)} first.
     *
     * @param labelValues the dynamic label values of the measurement, must not be {@code null}
     * @param measurement the measurement to add, must not be {@code null}
     * @return {@code true} if the measurement was added, {@code false} if there is already a measurement
     * with the given label values
     */
    public boolean putIfAbsent(@NonNull LabelValues labelValues, @NonNull M measurement) {
        // Not computeIfAbsent: creating the snapshot may call user code, for example an observed value supplier,
        // which must not run under the map bin lock. There it would block other writers to the bin, and changing
        // this container from it could fail with a recursive update. A snapshot is wasted only when another thread
        // adds the same label values concurrently and this call returns false.
        if (entries.putIfAbsent(labelValues, newEntry(labelValues, measurement)) != null) {
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
     * Returns the snapshots of the current measurements in a new mutable list, if measurements were added or removed
     * since the last call. Package private, because it is called only by the metric snapshot during export,
     * and only if the metric is exported, so every measurement has a snapshot.
     * <p>
     * Measurements may be added and removed during the call, so the list may contain two snapshots for label values
     * removed and added again meanwhile. Both changes mark the measurements again, so the next call returns them.
     *
     * @return the snapshots, or {@code null} if nothing changed since the last call
     */
    @Nullable
    List<MeasurementSnapshot> snapshotsIfChanged() {
        // read first, so an export without changes does not write the flag; cleared before reading the measurements,
        // so a change made after clearing marks them again for the next export
        if (!changed.get() || !changed.getAndSet(false)) {
            return null;
        }
        final List<MeasurementSnapshot> snapshots = new ArrayList<>(entries.size());
        for (Entry<M> entry : entries.values()) {
            snapshots.add(entry.snapshot);
        }
        return snapshots;
    }

    private Entry<M> newEntry(LabelValues labelValues, M measurement) {
        Objects.requireNonNull(measurement, "measurement must not be null");
        if (!exported) {
            return new Entry<>(measurement, null);
        }
        final MeasurementSnapshot snapshot = Objects.requireNonNull(
                snapshotFactory.apply(measurement, labelValues), "measurement snapshot must not be null");
        return new Entry<>(measurement, snapshot);
    }

    private void markChanged() {
        changed.set(true);
    }

    /**
     * A measurement with its snapshot, created once together, because creating a snapshot reads the measurement value.
     * <p>
     * Not a record: {@link ConcurrentHashMap#remove(Object, Object)} in {@link #removeIf(Predicate)} compares entries
     * with {@code equals}, which must stay identity based. A record would compare the measurements, and snapshots are
     * {@code null} if the metric is not exported, so a stale removal could remove a measurement with equal value
     * added again for the same label values.
     */
    private static final class Entry<M> {
        private final M measurement;

        @Nullable
        private final MeasurementSnapshot snapshot;

        private Entry(M measurement, @Nullable MeasurementSnapshot snapshot) {
            this.measurement = measurement;
            this.snapshot = snapshot;
        }
    }
}
