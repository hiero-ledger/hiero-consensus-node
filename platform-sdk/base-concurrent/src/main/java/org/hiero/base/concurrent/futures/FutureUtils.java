// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent.futures;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Utility methods for working with {@link Future} instances.
 */
public final class FutureUtils {

    private FutureUtils() {}

    /**
     * Waits for all futures in the given map to complete and returns a new map with the same keys mapped to the
     * resolved values.
     *
     * @param futures a map of keys to futures
     * @param <K> the key type
     * @param <V> the value type
     * @return a new map with the same keys mapped to resolved future values
     * @throws ExecutionException if any future's computation threw an exception
     * @throws InterruptedException if the current thread was interrupted while waiting
     */
    @NonNull
    public static <K, V> Map<K, V> awaitAll(@NonNull final Map<K, Future<V>> futures)
            throws ExecutionException, InterruptedException {
        final Map<K, V> map = HashMap.newHashMap(futures.size());
        for (final Map.Entry<K, Future<V>> entry : futures.entrySet()) {
            map.put(entry.getKey(), entry.getValue().get());
        }
        return map;
    }

    /**
     * Waits for all futures in the given list to complete and returns a new list of futures results.
     *
     * @param futures a list of futures
     * @param <V> the value type
     * @return a new list futures results or nulls if future was canceled
     * @throws ExecutionException if any future's computation threw an exception
     * @throws InterruptedException if the current thread was interrupted while waiting
     */
    public static <V> List<V> awaitAll(@NonNull final List<Future<V>> futures)
            throws ExecutionException, InterruptedException {
        final List<V> results = new ArrayList<>(futures.size());
        for (final Future<V> future : futures) {
            if (future.isCancelled()) {
                results.add(null);
            } else {
                results.add(future.get());
            }
        }
        return results;
    }
}
