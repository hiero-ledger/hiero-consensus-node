// SPDX-License-Identifier: Apache-2.0
package com.swirlds.fchashmap.internal;

import com.swirlds.fchashmap.FCHashMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;

/**
 * An entry set view for {@link FCHashMap}.
 *
 * @param <K>
 * 		the type of the map's key
 * @param <V>
 * 		the type of the map's value
 */
public class FCHashMapEntrySet<K, V> extends AbstractSet<Map.Entry<K, V>> {

    private final FCHashMap<K, V> map;
    private final FCHashMapFamily<K, V> family;

    /**
     * Create a new entry set view for an {@link FCHashMap}.
     *
     * @param map
     * 		the map that will be represented by this set
     */
    public FCHashMapEntrySet(final FCHashMap<K, V> map, final FCHashMapFamily<K, V> family) {
        this.map = map;
        this.family = family;
    }

    /**
     * {@inheritDoc}
     */
    public Iterator<Map.Entry<K, V>> iterator() {
        return new FCHashMapEntrySetIterator<>(map, family.keyIterator());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int size() {
        return map.size();
    }

    /**
     * Equals isn't implemented, because it would be extremely inefficient
     *
     * @param o
     * 		object to be compared for equality with this set
     * @throws UnsupportedOperationException
     * 		if called
     */
    @Override
    public boolean equals(final Object o) {
        throw new UnsupportedOperationException();
    }

    /**
     * HashCode isn't implemented, because it would be extremely inefficient
     *
     * @throws UnsupportedOperationException
     * 		if called
     */
    @Override
    public int hashCode() {
        throw new UnsupportedOperationException();
    }
}
