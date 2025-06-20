// SPDX-License-Identifier: Apache-2.0
package com.swirlds.fchashmap;

import com.swirlds.common.FastCopyable;
import com.swirlds.fchashmap.internal.FCOneToManyRelationIterator;
import com.swirlds.fchashmap.internal.KeyIndexPair;
import com.swirlds.fchashmap.internal.KeyValuePair;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * This class implements a fast copyable one-to-many relation map. This class is
 * analogous to a {@code Map<K, Set<V>>} but with the ability to make fast copies.
 *
 * @param <K>
 * 		the type of the key. Type must be effectively immutable -- that is, once inserted into this data structure
 * 		its equals() and hashcode() must always yield the same results.
 * @param <V>
 * 		the type of the value. Type must be effectively immutable -- that is, once inserted into this data structure
 * 		its equals() and hashcode() must always yield the same results.
 */
public class FCOneToManyRelation<K, V> implements FastCopyable {

    /**
     * A map of indexed associations.
     */
    private final FCHashMap<KeyIndexPair<K>, V> associationMap;

    /**
     * A map containing the number of associations for each key.
     */
    private final FCHashMap<K, Integer> associationCountMap;

    /**
     * A map of each association to its index.
     * <p>
     * This data structure is only required for operations that update the mutable copy of the this object
     * (i.e. gap filling). When this FCOneToManyRelation is copied and becomes immutable, this map is set to null.
     */
    private Map<KeyValuePair<K, V>, Integer> indexMap;

    private boolean immutable;

    private boolean released;

    /**
     * Create a new {@link FCOneToManyRelation}.
     */
    public FCOneToManyRelation() {
        this.associationMap = new FCHashMap<>();
        this.associationCountMap = new FCHashMap<>();
        this.indexMap = new HashMap<>();
        this.immutable = false;
    }

    /**
     * Copy constructor.
     *
     * @param that
     * 		the object to copy. This object is mutable, but that object becomes immutable.
     */
    private FCOneToManyRelation(final FCOneToManyRelation<K, V> that) {
        this.associationMap = that.associationMap.copy();
        this.associationCountMap = that.associationCountMap.copy();
        this.indexMap = that.indexMap;
        that.indexMap = null;
        that.immutable = true;
        this.immutable = false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isImmutable() {
        return immutable;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public FCOneToManyRelation<K, V> copy() {
        throwIfImmutable();
        return new FCOneToManyRelation<>(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean release() {
        throwIfDestroyed();
        associationMap.release();
        associationCountMap.release();
        released = true;
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isDestroyed() {
        return released;
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
     * Add a new association between a key and a value.
     * <p>
     * Has no effect if the key and value already has an association.
     *
     * @param key
     * 		the key will be associated with the value
     * @param value
     * 		the value that will be associated with the key
     * @return true if a new association is made,
     * 		false if the key and value are already associated
     */
    public boolean associate(final K key, final V value) {
        throwIfImmutable();
        throwIfNullKey(key);
        throwIfNullValue(value);

        final KeyValuePair<K, V> association = new KeyValuePair<>(key, value);
        if (isAssociated(association)) {
            // key and value are already associated
            return false;
        }

        final int index = associationCountMap.getOrDefault(key, 0);
        associationMap.put(new KeyIndexPair<>(key, index), value);
        associationCountMap.put(key, index + 1);
        indexMap.put(association, index);

        return true;
    }

    /**
     * Break an association between a key and a value. Has no effect if the key is not associated with the value.
     *
     * @param key
     * 		the key may be associated with a value
     * @param value
     * 		the value that the key references
     * @return true the association is broken,
     * 		false if the key and value were not associated in the first place
     */
    public boolean disassociate(final K key, final V value) {
        throwIfImmutable();
        throwIfNullKey(key);
        throwIfNullValue(value);

        final KeyValuePair<K, V> associationToDissolve = new KeyValuePair<>(key, value);
        if (!isAssociated(associationToDissolve)) {
            // The value is not associated this key
            return false;
        }

        final int indexToRemove = indexMap.get(associationToDissolve);
        final int maxIndex = associationCountMap.get(key) - 1;

        final KeyIndexPair<K> keyIndexPairToRemove = new KeyIndexPair<>(key, indexToRemove);
        if (indexToRemove == maxIndex) {
            associationMap.remove(keyIndexPairToRemove);
        } else {
            // We need to fill the gap, indices must be must not skipped
            final V gapFillingValue = associationMap.remove(new KeyIndexPair<>(key, maxIndex));
            associationMap.put(keyIndexPairToRemove, gapFillingValue);
            indexMap.put(new KeyValuePair<>(key, gapFillingValue), indexToRemove);
        }

        if (maxIndex == 0) {
            associationCountMap.remove(key);
        } else {
            associationCountMap.put(key, maxIndex);
        }

        indexMap.remove(associationToDissolve);

        return true;
    }

    /**
     * Check if an association is present.
     *
     * @param association
     * 		the association in question
     * @return true if the association is present in the data structure
     */
    private boolean isAssociated(final KeyValuePair<K, V> association) {
        return indexMap.containsKey(association);
    }

    /**
     * Given a key, return an iterator that iterates over the set of associated values.
     * <p>
     * This iterator should not used after this data structure is updated in any way.
     * This iterator may exhibit undefined behavior if this copy of the data structure is updated between
     * the creation of this iterator and the last use of this iterator.
     *
     * @param key
     * 		the key in question
     * @return an iterator for the set of associated values
     */
    public Iterator<V> get(final K key) {
        return get(key, 0, getCount(key));
    }

    /**
     * Given a key, return an iterator that iterates over the set of associated values. Iteration
     * starts at a given index and continues until the end of the value set.
     * <p>
     * This iterator should not used after this data structure is updated in any way.
     * This iterator may exhibit undefined behavior if this copy of the data structure is updated between
     * the creation of this iterator and the last use of this iterator.
     *
     * @param key
     * 		the key in question
     * @param startIndex
     * 		the index of the first value to return (inclusive).
     * 		An index of 0 starts iteration at the first value in the set.
     * @return an iterator for the key set starting at the given index
     * @throws IndexOutOfBoundsException
     * 		if the requested start index is invalid
     */
    public Iterator<V> get(final K key, final int startIndex) {
        return get(key, startIndex, getCount(key));
    }

    /**
     * Given a key, return an iterator that iterates over a paginated set of associated values.
     * <p>
     * This iterator should not used after this data structure is updated in any way.
     * This iterator may exhibit undefined behavior if this copy of the data structure is updated between
     * the creation of this iterator and the last use of this iterator.
     *
     * @param key
     * 		the key in question
     * @param startIndex
     * 		the index of the first value to return (inclusive).
     * 		An index of 0 starts iteration at the first value in the set.
     * @param endIndex
     * 		the index bounding the last value to return (exclusive).
     * 		An index of N will cause the set to include the last element if there are N elements.
     * @return an iterator for the value set for the over the requested indices
     * @throws IndexOutOfBoundsException
     * 		if the requested indices are invalid
     */
    public Iterator<V> get(final K key, final int startIndex, final int endIndex) {
        throwIfNullKey(key);
        if (startIndex < 0) {
            throw new IndexOutOfBoundsException("negative indices are not supported");
        }
        if (startIndex > endIndex) {
            throw new IndexOutOfBoundsException("start index exceeds end index");
        }
        final int count = getCount(key);
        if (count < endIndex) {
            throw new IndexOutOfBoundsException("end index " + endIndex + " requested but there "
                    + (count == 1 ? "is" : "are") + " " + count + " " + (count == 1 ? "entry" : "entries"));
        }
        return new FCOneToManyRelationIterator<>(associationMap, key, startIndex, endIndex);
    }

    /**
     * Given a key, return a list of associated values.
     *
     * @param key
     * 		the key in question
     * @return a list of associated values
     */
    public List<V> getList(final K key) {
        return getList(key, 0, getCount(key));
    }

    /**
     * Given a key, return a list of associated values.
     *
     * @param key
     * 		the key in question
     * @param startIndex
     * 		the index of the first key to return (inclusive).
     * 		An index of 0 starts at the first value associated with the key.
     * @return a list of associated values
     */
    public List<V> getList(final K key, final int startIndex) {
        return getList(key, startIndex, getCount(key));
    }

    /**
     * Given a key, return a list of associated values.
     *
     * @param key
     * 		the key in question
     * @param startIndex
     * 		the index of the first key to return (inclusive).
     * 		An index of 0 starts at the first value associated with the key.
     * @param endIndex
     * 		the index bounding the last value to return (exclusive).
     * 		An index of N will cause the list to include the last element if there are N elements.
     * @return a list of associated values
     */
    public List<V> getList(final K key, final int startIndex, final int endIndex) {
        final List<V> list = new ArrayList<>(getCount(key));
        final Iterator<V> iterator = get(key, startIndex, endIndex);
        iterator.forEachRemaining(list::add);
        return list;
    }

    /**
     * Return a count of the number of values associated with a key.
     *
     * @param key
     * 		the key in question
     * @return the number of values associated with the given key
     */
    public int getCount(final K key) {
        throwIfNullKey(key);
        final Integer count = associationCountMap.get(key);
        if (count == null) {
            return 0;
        }
        return count;
    }

    /**
     * Get the total number of keys in the association.
     */
    public int getKeyCount() {
        return associationCountMap.size();
    }

    /**
     * Get a set containing all keys in the association.
     */
    public Set<K> getKeySet() {
        return associationCountMap.keySet();
    }
}
