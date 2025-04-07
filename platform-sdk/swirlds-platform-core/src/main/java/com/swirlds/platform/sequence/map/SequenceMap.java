// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.sequence.map;

import com.swirlds.platform.sequence.Shiftable;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.hiero.base.utility.Clearable;

/**
 * <p>
 * A map-like object whose keys have an associated sequence number. Multiple keys may have the same sequence number.
 * </p>
 *
 * <p>
 * The sequence number of any particular object in this data structure is not permitted to change.
 * </p>
 *
 * <p>
 * This data structure is designed around use cases where the sequence number of new objects trends upwards over time.
 * This data structure may be significantly less useful for use cases where the window of allowable
 * sequence numbers shifts backwards and forwards arbitrarily.
 * </p>
 *
 * <p>
 * This data structure manages the allowed window of sequence numbers. That is, it allows a minimum sequence number
 * and a capacity to be specified, and ensures that entries that violate that window are removed and not allowed
 * to enter. Increasing the minimum value allowed in the window is also called "purging", as it may cause values in the
 * map to be removed if they fall outside the window.
 * </p>
 *
 * <p>
 * This data structure also allows all values with a particular sequence number to be efficiently
 * retrieved and deleted.
 * </p>
 *
 * @param <K>
 * 		the type of key
 * @param <V>
 * 		the type of value
 */
public interface SequenceMap<K, V> extends Clearable, Shiftable {

    /**
     * Get the value for a key. Returns null if the key is not in the map. Also returns null if the key was once in the
     * map but has since been purged.
     *
     * @param key
     * 		the key
     * @return the value, or null if the key is not present or has been purged
     */
    V get(K key);

    /**
     * Check if the map contains a key. Returns false if the key is not in the map. Also returns false if the key was
     * once in the map but has since been purged.
     *
     * @param key
     * 		the key in question
     * @return true if the map currently contains the key
     */
    boolean containsKey(K key);

    /**
     * Get the value for a key. If none exists and the key's sequence number is permitted then create one.
     *
     * @param key
     * 		the key
     * @return the original value if present, or the new value, or null if the value is outside the allowed window
     */
    V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction);

    /**
     * Insert a value if there is currently no entry for the value, and it is legal to insert the key's sequence
     * number (if this key's sequence number has previously been purged then this insertion will
     * never be considered legal). Does not override the existing value if there is already an entry for this key
     * in the map.
     *
     * @param key
     * 		the key
     * @param value
     * 		the value
     * @return true if the value was inserted, false if it was not inserted for any reason
     */
    boolean putIfAbsent(K key, V value);

    /**
     * Insert a value into the map. No-op if key has a purged sequence number.
     *
     * @param key
     * 		the key
     * @param value
     * 		the value
     * @return the previous value, or null if there was no previous value (or if the previous value was purged)
     */
    V put(K key, V value);

    /**
     * Remove an entry from the map.
     *
     * @param key
     * 		the entry to remove
     * @return the removed value, or null if the key was not present
     */
    V remove(K key);

    /**
     * Remove all keys with a given sequence number. Does not adjust the window of allowable sequence numbers,
     * and so keys with this sequence number will not be rejected in the future.
     *
     * @param sequenceNumber
     * 		all keys with this sequence number will be removed
     */
    default void removeValuesWithSequenceNumber(final long sequenceNumber) {
        removeValuesWithSequenceNumber(sequenceNumber, null);
    }

    /**
     * Remove all keys with a given sequence number. Does not adjust the window of allowable sequence numbers,
     * and so keys with this sequence number will not be rejected in the future.
     *
     * @param sequenceNumber
     * 		all keys with this sequence number will be removed
     * @param removedValueHandler
     * 		a callback that is passed all key/value pairs that are removed. Ignored if null.
     */
    void removeValuesWithSequenceNumber(final long sequenceNumber, BiConsumer<K, V> removedValueHandler);

    /**
     * Get a list of all keys with a given sequence number. Once the list is returned, it is safe to modify
     * the list without effecting the map that returned it. However, modification of the objects within the list
     * may modify objects in the map that returned it.
     *
     * @param sequenceNumber
     * 		the sequence number to get
     * @return a list of keys that have the given sequence number
     */
    List<K> getKeysWithSequenceNumber(final long sequenceNumber);

    /**
     * Get a list of all entries with a given sequence number. Once the list is returned, it is safe to modify
     * the list without effecting the map that returned it. However, modification of the objects within the list
     * may modify objects in the map that returned it.
     *
     * @param sequenceNumber
     * 		the sequence number to get
     * @return a list of entries that have the given sequence number
     */
    List<Map.Entry<K, V>> getEntriesWithSequenceNumber(final long sequenceNumber);

    /**
     * {@inheritDoc}
     */
    @Override
    default void shiftWindow(final long firstSequenceNumberInWindow) {
        shiftWindow(firstSequenceNumberInWindow, null);
    }

    /**
     * <p>
     * Remove all keys that have a sequence number smaller than a specified value, and increase the maximum allowed
     * sequence number by the same amount. After this operation, all keys outside the new window will be rejected.
     * </p>
     *
     * <p>
     * The smallest allowed sequence number must only increase over time.
     * </p>
     *
     * @param firstSequenceNumberInWindow
     * 		the first sequence number in the new window,
     * 		all keys with a sequence number strictly smaller than this value will be removed
     * @param removedValueHandler
     * 		this value is passed each key/value pair that is removed as a result of this operation, ignored if null
     * @throws IllegalStateException
     * 		if the window is shifted to the left (towards smaller values)
     */
    void shiftWindow(long firstSequenceNumberInWindow, BiConsumer<K, V> removedValueHandler);

    /**
     * @return the number of entries in this map
     */
    int getSize();

    /**
     * Get the minimum sequence number that is permitted to be in this map. All keys with smaller
     * sequence numbers have been removed, and any key added in the future will be rejected
     * if it has a smaller sequence number.
     *
     * @return the smallest allowed sequence number
     */
    long getFirstSequenceNumberInWindow();

    /**
     * Get the maximum sequence number that is permitted to be in this map. All keys with larger
     * sequence numbers have been rejected, and any key added in the future will be rejected
     * if it has a larger sequence number than what is returned by this method at that time.
     *
     * @return the largest allowed sequence number
     */
    default long getLastSequenceNumberInWindow() {
        return getFirstSequenceNumberInWindow() + getSequenceNumberCapacity() - 1;
    }

    /**
     * Get the capacity of this map, in number of sequence numbers allowed.
     *
     * @return the sequence number capacity
     */
    int getSequenceNumberCapacity();
}
