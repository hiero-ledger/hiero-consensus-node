// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Comparator;
import java.util.List;

/**
 * Builds the lists of snapshots iterated by an export.
 * Package private, because it is used only to rebuild metric and registry snapshots.
 */
final class SnapshotLists {

    private SnapshotLists() {}

    /**
     * Sorts snapshots collected from a concurrent map and keeps only the last one of the equal snapshots.
     * <p>
     * Iteration over a {@link java.util.concurrent.ConcurrentHashMap} returns an entry removed and added again
     * during the iteration twice, if the new entry is appended to the same bin after the removed one was returned.
     * The sort is stable, so equal snapshots stay in iteration order and the later, added again one is kept.
     *
     * @param snapshots  the snapshots in iteration order, sorted in place
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
