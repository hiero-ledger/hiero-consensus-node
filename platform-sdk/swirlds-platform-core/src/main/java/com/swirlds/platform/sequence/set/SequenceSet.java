// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.sequence.set;

import com.swirlds.platform.sequence.Shiftable;
import java.util.List;
import java.util.function.Consumer;
import org.hiero.base.utility.Clearable;

/**
 * <p>
 * A set-like object whose entries have an associated sequence number.
 * Multiple entries may have the same sequence number.
 * </p>
 *
 * <p>
 * This set does not support null entries.
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
 * This data structure manages the allowed window of sequence numbers. That is, it allows a minimum sequence
 * number and capacity to be specified, and ensures that entries that violate that window are removed and not allowed
 * to enter.
 * </p>
 *
 * <p>
 * This data structure also allows all entries with a particular sequence number to be efficiently
 * retrieved and deleted.
 * </p>
 *
 * @param <T>
 * 		the type of the entry contained within this set
 */
public interface SequenceSet<T> extends Clearable, Shiftable {

    /**
     * Add an element to the set. Element is rejected if the element is already in the set, or if the sequence
     * number for the element falls outside the allowed limits.
     *
     * @param element
     * 		the element to add
     * @return true if the element was added, otherwise false
     */
    boolean add(T element);

    /**
     * Remove an element from the set if it is present.
     *
     * @param element
     * 		the element to remove
     * @return true if the element was removed, false if the element was not in the set
     */
    boolean remove(T element);

    /**
     * Check if an element is currently contained within the set.
     *
     * @param element
     * 		the element in question
     * @return true if the element is contained within the set, otherwise false
     */
    boolean contains(T element);

    /**
     * Remove all elements with a given sequence number. Does not adjust the window of allowable sequence numbers,
     * and so elements with this sequence number will not be rejected in the future.
     *
     * @param sequenceNumber
     * 		all elements with this sequence number will be removed
     */
    default void removeSequenceNumber(final long sequenceNumber) {
        removeSequenceNumber(sequenceNumber, null);
    }

    /**
     * Remove all elements with a given sequence number. Does not adjust the window of allowable sequence numbers,
     * and so elements with this sequence number will not be rejected in the future.
     *
     * @param sequenceNumber
     * 		all elements with this sequence number will be removed
     * @param removedElementHandler
     * 		a callback that is passed all elements that are removed. Ignored if null.
     */
    void removeSequenceNumber(final long sequenceNumber, Consumer<T> removedElementHandler);

    /**
     * Get a list of all entries with a given sequence number. Once the list is returned, it is safe to modify
     * the list without effecting the set that returned it. However, modification of the objects within the list
     * may modify objects in the set that returned it.
     *
     * @param sequenceNumber
     * 		the sequence number to get
     * @return a list of entries that have the given sequence number
     */
    List<T> getEntriesWithSequenceNumber(final long sequenceNumber);

    /**
     * {@inheritDoc}
     */
    @Override
    default void shiftWindow(final long lowestAllowedSequenceNumber) {
        shiftWindow(lowestAllowedSequenceNumber, null);
    }

    /**
     * <p>
     * Purge all keys that have a sequence number smaller than a specified value. After this operation,
     * all keys with a smaller sequence number will be rejected if insertion is attempted.
     * </p>
     *
     * <p>
     * The smallest allowed sequence number must only increase over time. If N&lt;=M and M has already been purged,
     * then purging N will have no effect.
     * </p>
     *
     * @param smallestAllowedSequenceNumber
     * 		all keys with a sequence number strictly smaller than this value will be removed
     * @param removedValueHandler
     * 		this value is passed each key/value pair that is removed as a result of this operation, ignored if null
     */
    void shiftWindow(long smallestAllowedSequenceNumber, Consumer<T> removedValueHandler);

    /**
     * @return the number of entries in this set
     */
    int getSize();

    /**
     * Get the capacity of this map, in number of sequence numbers allowed.
     *
     * @return the sequence number capacity
     */
    int getSequenceNumberCapacity();

    /**
     * Get the minimum sequence number that is permitted to be in this set. All entries with smaller
     * sequence numbers have been removed, and any entry added in the future will be rejected
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
    long getLastSequenceNumberInWindow();
}
