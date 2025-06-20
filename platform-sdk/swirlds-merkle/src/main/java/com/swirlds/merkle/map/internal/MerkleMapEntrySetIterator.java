// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.map.internal;

import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.utility.Keyed;
import java.util.Iterator;
import java.util.Map;

/**
 * <p>This iterator walks over a {@link MerkleMapEntrySet}</p>
 * <p>This iterator leverages the functionality of the FCHashMapEntrySetIterator</p>
 *
 * @param <K>
 * 		the type of the map's key
 * @param <V>
 * 		the type of the map's value
 */
public class MerkleMapEntrySetIterator<K, V extends MerkleNode & Keyed<K>> implements Iterator<Map.Entry<K, V>> {
    /**
     * An iterator for the MerkleMap's FCHashMap
     */
    private final Iterator<Map.Entry<K, V>> hashMapIterator;

    /**
     * Constructor
     *
     * @param hashMapIterator
     * 		the iterator from the FCHashMap which belongs to the MerkleMap
     */
    public MerkleMapEntrySetIterator(final Iterator<Map.Entry<K, V>> hashMapIterator) {
        this.hashMapIterator = hashMapIterator;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasNext() {
        return hashMapIterator.hasNext();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map.Entry<K, V> next() {
        return hashMapIterator.next();
    }

    /**
     * The remove() operation is unsupported, due to the complexity of removing elements from the MerkleMap while
     * iterating over the FCHashMap
     *
     * @throws UnsupportedOperationException
     * 		if called
     */
    @Override
    public void remove() {
        throw new UnsupportedOperationException();
    }
}
