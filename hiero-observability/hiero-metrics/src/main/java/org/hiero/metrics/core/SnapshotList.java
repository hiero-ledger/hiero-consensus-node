// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A sorted list of snapshots iterated by exports, which is rebuilt from its source only after the source changed.
 * Used for the metric snapshots of a {@link MetricRegistrySnapshot} and the measurement snapshots of a
 * {@link MetricSnapshot}, kept by the {@link Measurements} of its metric.
 * <p>
 * Any thread changing the source calls {@link #markChanged()} after the change is visible in it. The next
 * {@link #update()} rebuilds the list from the source, and then updates the values of all snapshots in it.
 * Every iteration reads a fixed list of snapshots, which later updates do not change, but later updates do overwrite
 * the values of the snapshots in it.
 * <p>
 * Snapshots are compared only by the given order, never by {@code equals} or identity, so the snapshot type
 * has no requirements of its own. The order alone decides which snapshots are duplicates of one key.
 * <p>
 * Package private, because it is used only to keep the snapshots exported by metrics and registries.
 *
 * @param <S> the snapshot type
 */
final class SnapshotList<S> implements Iterable<S> {

    private final Comparator<? super S> order;
    private final Supplier<? extends List<S>> collector;
    private final Consumer<? super S> updater;

    // set by any thread after the source changed, cleared by the update rebuilding the list
    private final AtomicBoolean changed = new AtomicBoolean(true);
    // replaced by a rebuilt list and never changed in place, so a started iteration is not affected
    private volatile List<S> snapshots = List.of();

    /**
     * Creates an empty list, which is rebuilt by the first {@link #update()}.
     * The collector and the updater are passed once here, so an update allocates nothing when nothing changed.
     *
     * @param order     sorts the snapshots by their keys in the source; must return {@code 0} only for snapshots
     *                  whose keys are equal in the source, because only one of the snapshots comparing equal is kept,
     *                  so distinct keys comparing equal would silently drop a snapshot from every export
     * @param collector returns a new mutable list of the snapshots of the source in its iteration order,
     *                  called only by {@link #update()}
     * @param updater   updates the values of a snapshot, called only by {@link #update()}
     */
    SnapshotList(
            @NonNull Comparator<? super S> order,
            @NonNull Supplier<? extends List<S>> collector,
            @NonNull Consumer<? super S> updater) {
        this.order = Objects.requireNonNull(order, "order must not be null");
        this.collector = Objects.requireNonNull(collector, "collector must not be null");
        this.updater = Objects.requireNonNull(updater, "updater must not be null");
    }

    /**
     * Marks the source as changed, so the next {@link #update()} rebuilds the list.
     * Called by any thread, after the change is visible in the source.
     */
    void markChanged() {
        changed.set(true);
    }

    /**
     * Rebuilds the list if the source changed since the last rebuild, and updates the values of all snapshots in it.
     * A rebuilt list replaces the iterated one only after its values are updated, and also if updating a value throws,
     * because the source is not marked as changed anymore. If the rebuild itself throws, the iterated list is kept
     * and the source stays marked, so the next update rebuilds it again.
     * <p>
     * Must be called by one thread at a time: the {@link MetricRegistrySnapshot} update is synchronized, and updates
     * the snapshots of all metrics.
     *
     * @return {@code true} if the list was rebuilt, {@code false} if the source did not change
     */
    boolean update() {
        final List<S> rebuilt = rebuildIfChanged();
        final List<S> current = rebuilt == null ? snapshots : rebuilt;
        try {
            for (S snapshot : current) {
                updater.accept(snapshot);
            }
        } finally {
            // published after the values are updated, so an iteration never reads a new snapshot without its values
            if (rebuilt != null) {
                snapshots = rebuilt;
            }
        }
        return rebuilt != null;
    }

    @NonNull
    @Override
    public Iterator<S> iterator() {
        return snapshots.iterator();
    }

    @Override
    public String toString() {
        return snapshots.toString();
    }

    /**
     * Collects and sorts the snapshots of the source, if it changed since the last call.
     * The list holds one snapshot per key. For a key removed and added again during the call, it may hold the
     * snapshot of the removed one. Adding it again marks the source again, so the next call returns its snapshot.
     *
     * @return the sorted, unmodifiable snapshots, or {@code null} if the source did not change since the last call
     */
    @Nullable
    private List<S> rebuildIfChanged() {
        if (!changed.compareAndSet(true, false)) {
            return null;
        }
        try {
            // keys removed and added again during the iteration may be collected twice: the later one is kept
            return sortKeepingLastOfEqual(collector.get(), order);
        } catch (RuntimeException | Error e) {
            markChanged();
            throw e;
        }
    }

    /**
     * Sorts snapshots collected from a concurrent map and keeps only the last one of the equal snapshots.
     * <p>
     * Iteration over a {@link java.util.concurrent.ConcurrentHashMap} returns an entry removed and added again
     * during the iteration twice, if the new entry is appended to the same bin after the removed one was returned.
     * The sort is stable, so equal snapshots stay in iteration order and the later, added again one is kept.
     * Package private for tests.
     *
     * @param snapshots  the snapshots in iteration order, sorted and compacted in place,
     *                   so its contents are unspecified after the call
     * @param comparator compares snapshots by their keys in the map
     * @param <T>        the snapshot type
     * @return unmodifiable sorted list without equal snapshots
     */
    static <T> List<T> sortKeepingLastOfEqual(@NonNull List<T> snapshots, @NonNull Comparator<? super T> comparator) {
        snapshots.sort(comparator);
        // compacts in place: the first size elements are the kept snapshots, and size never passes i
        int size = 0;
        for (int i = 0; i < snapshots.size(); i++) {
            final T snapshot = snapshots.get(i);
            if (size > 0 && comparator.compare(snapshots.get(size - 1), snapshot) == 0) {
                // equal to the last kept one, which comes earlier in iteration order: replace it with this later one
                snapshots.set(size - 1, snapshot);
            } else {
                // first snapshot with this key: keep it after the last kept one
                snapshots.set(size++, snapshot);
            }
        }
        return List.copyOf(snapshots.subList(0, size));
    }
}
