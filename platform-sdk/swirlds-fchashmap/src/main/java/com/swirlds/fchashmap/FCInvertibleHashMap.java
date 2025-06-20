// SPDX-License-Identifier: Apache-2.0
package com.swirlds.fchashmap;

import com.swirlds.common.FastCopyable;
import java.util.AbstractMap;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A fast copyable hashmap that is also able to generate inverse mappings between a value and the set of all
 * keys that map to that value. Supports pagination.
 * <p>
 * This data structure does not support null values.
 *
 * @param <K>
 * 		the type of the key. This key must be effectively immutable -- that is, no changes to the key after its
 * 		insertion may have any effect on its {@link Object#equals(Object)} or {@link Object#hashCode()}.
 * @param <V>
 * 		the type of the value. This key must be effectively immutable -- that is, no changes to the value after its
 * 		insertion may have any effect on its {@link Object#equals(Object)} or {@link Object#hashCode()}.
 */
public class FCInvertibleHashMap<K, V> extends AbstractMap<K, V> implements FastCopyable {

    private final FCHashMap<K, V> map;
    private final FCOneToManyRelation<V, K> inverseRelation;
    private boolean immutable;

    /**
     * Create a new {@link FCInvertibleHashMap}.
     */
    public FCInvertibleHashMap() {
        this(0);
    }

    /**
     * Create a new {@link FCInvertibleHashMap} with an initial capacity.
     *
     * @param capacity
     * 		the initial capacity of the hashmap
     */
    public FCInvertibleHashMap(final int capacity) {
        this.map = new FCHashMap<>(capacity);
        this.inverseRelation = new FCOneToManyRelation<>();
        this.immutable = false;
    }

    /**
     * Copy constructor.
     *
     * @param that
     * 		the map to copy
     */
    private FCInvertibleHashMap(final FCInvertibleHashMap<K, V> that) {
        this.map = that.map.copy();
        this.inverseRelation = that.inverseRelation.copy();
        that.immutable = true;
        this.immutable = false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public FCInvertibleHashMap<K, V> copy() {
        return new FCInvertibleHashMap<>(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isImmutable() {
        return immutable;
    }

    /**
     * Utility function. Throw if the key is null.
     */
    private void throwIfNullKey(final K key) {
        if (key == null) {
            throw new IllegalArgumentException("null keys are not supported");
        }
    }

    /**
     * Utility function. Throw if the value is null.
     */
    private void throwIfNullValue(final V value) {
        if (value == null) {
            throw new IllegalArgumentException("null values are not supported");
        }
    }

    /**
     * Given a value, return an iterator that iterates over the set of keys that map to the value.
     * <p>
     * This iterator should not used after this copy of the map is updated in any way.
     * This iterator may exhibit undefined behavior if this copy of the map is updated between
     * the creation of this iterator and the use of this iterator.
     *
     * @param value
     * 		the value in question
     * @return an iterator for the key set
     */
    public Iterator<K> inverseGet(final V value) {
        return inverseRelation.get(value);
    }

    /**
     * Given a value, return an iterator that iterates over a paginated set of keys that map to the value. Iteration
     * starts at a given index and continues until the end of the key set.
     * <p>
     * This iterator should not used after this copy of the map is updated in any way.
     * This iterator may exhibit undefined behavior if this copy of the map is updated between
     * the creation of this iterator and the use of this iterator.
     *
     * @param value
     * 		the value in question
     * @param startIndex
     * 		the index of the first key to return (inclusive).
     * 		An index of 0 starts iteration at the first key in the set.
     * @return an iterator for the key set starting at the given index
     */
    public Iterator<K> inverseGet(final V value, final int startIndex) {
        return inverseRelation.get(value, startIndex);
    }

    /**
     * Given a value, return an iterator that iterates over a paginated set of keys that map to the value.
     * <p>
     * This iterator should not used after this copy of the map is updated in any way.
     * This iterator may exhibit undefined behavior if this copy of the map is updated between
     * the creation of this iterator and the use of this iterator.
     *
     * @param value
     * 		the value in question
     * @param startIndex
     * 		the index of the first key to return (inclusive).
     * 		An index of 0 starts iteration at the first key in the set.
     * @param endIndex
     * 		the index bounding the last key to return (exclusive).
     * 		An index of N will cause the set to include the last element if there are N elements.
     * @return an iterator for the key set starting at the given index
     */
    public Iterator<K> inverseGet(final V value, final int startIndex, final int endIndex) {
        return inverseRelation.get(value, startIndex, endIndex);
    }

    /**
     * Given a value, return a list that contains a list of keys that map to that value.
     *
     * @return an list containing the requested range
     */
    public List<K> inverseGetList(final V value) {
        return inverseRelation.getList(value);
    }

    /**
     * Given a value, return a list that contains a list of keys that start at a given index.
     *
     * @param startIndex
     * 		the index of the first key to return (inclusive).
     * 		An index of 0 starts at the first key in the set.
     * @return an list containing the requested range
     */
    public List<K> inverseGetList(final V value, final int startIndex) {
        return inverseRelation.getList(value, startIndex);
    }

    /**
     * Given a value, return a list that contains a paginated list of keys that map to the value.
     *
     * @param startIndex
     * 		the index of the first key to return (inclusive).
     * 		An index of 0 starts at the first key in the set.
     * @param endIndex
     * 		the index bounding the last key to return (exclusive).
     * 		An index of N will cause the set to include the last element if there are N elements.
     * @return an list containing the requested range
     */
    public List<K> inverseGetList(final V value, final int startIndex, final int endIndex) {
        return inverseRelation.getList(value, startIndex, endIndex);
    }

    /**
     * Return a count of the number of keys that reference a given value, i.e. the number of times that a value
     * appears in the map.
     *
     * @param value
     * 		the value in question
     * @return the number of keys that reference the value
     */
    public int getValueCount(final V value) {
        throwIfNullValue(value);
        return inverseRelation.getCount(value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized boolean release() {
        map.release();
        inverseRelation.release();
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public V put(final K key, final V value) {
        throwIfImmutable();
        throwIfNullKey(key);
        throwIfNullValue(value);

        final V previousValue = map.get(key);
        if (Objects.equals(previousValue, value)) {
            return previousValue;
        }

        if (previousValue != null) {
            final boolean disassociated = inverseRelation.disassociate(previousValue, key);
            if (!disassociated) {
                // Sanity check, this should never happen
                throw new IllegalStateException("unsuccessful disassociation");
            }
        }

        map.put(key, value);
        final boolean associated = inverseRelation.associate(value, key);
        if (!associated) {
            // Sanity check, this should never happen
            throw new IllegalStateException("unsuccessful association");
        }

        return previousValue;
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("unchecked")
    @Override
    public V remove(final Object key) {
        throwIfImmutable();
        throwIfNullKey((K) key);

        final V value = map.remove(key);
        if (value != null) {
            final boolean disassociated = inverseRelation.disassociate(value, (K) key);
            if (!disassociated) {
                // Sanity check, this should never happen
                throw new IllegalStateException("unsuccessful disassociation");
            }
        }
        return value;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<Entry<K, V>> entrySet() {
        return map.entrySet();
    }
}
