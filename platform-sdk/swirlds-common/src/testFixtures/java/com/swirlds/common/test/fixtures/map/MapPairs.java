// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.map;

import com.swirlds.common.FastCopyable;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Holds the mutable map pair and immutable copies
 *
 * @param <M>
 * 		map type
 * @param <K>
 * 		key type
 * @param <V>
 * 		value type
 */
public class MapPairs<M extends Map<K, V> & FastCopyable, K, V> {
    private final List<MapPair<M, K, V>> copies;
    private MapPair<M, K, V> mutablePair;

    public MapPairs(MapPair<M, K, V> mutablePair) {
        this.mutablePair = mutablePair;
        this.copies = new LinkedList<>();
    }

    /**
     * @return the current mutable pair
     */
    public MapPair<M, K, V> getMutablePair() {
        return mutablePair;
    }

    /**
     * copies the mutable pair and adds it to the list of immatable pairs
     */
    public void copy() {
        MapPair<M, K, V> copy = mutablePair.copy();
        copies.add(mutablePair);
        mutablePair = copy;
    }

    /**
     * @return the list of immutable copies
     */
    public List<MapPair<M, K, V>> getCopies() {
        return copies;
    }
}
