// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.sequence.map;

import com.swirlds.base.utility.ToStringBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.AbstractMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * Boilerplate implementation for {@link SequenceMap}.
 *
 * @param <K> the type of the key
 * @param <V> the type of the value
 */
public abstract class AbstractSequenceMap<K, V> implements SequenceMap<K, V> {

    /**
     * The maximum supported size of an array is JVM dependant, but it's usually a little smaller than the maximum
     * integer size. Various sources suggest this is a generally safe value to use.
     */
    private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;

    /**
     * The data in the map.
     */
    private final Map<K, V> data;

    /**
     * Keys for each sequence number currently being stored.
     */
    private SequenceKeySet<K>[] keySets;

    /**
     * The current capacity for sequence numbers. Equal to the maximum sequence number minus the minimum sequence
     * number. If {@link #allowExpansion} is true, then this value can be increased. If not, it is fixed.
     */
    private int sequenceNumberCapacity;

    /**
     * A method that gets the sequence number associated with a given key.
     */
    private final ToLongFunction<K> getSequenceNumberFromKey;

    /**
     * When this object is cleared, the lowest allowed sequence number is reset to this value.
     */
    private final long initialFirstSequenceNumber;

    /**
     * If true, expand when we get a high sequence number that does not fit. If false, reject the element.
     */
    private final boolean allowExpansion;

    /**
     * Construct an abstract sequence map.
     *
     * @param initialFirstSequenceNumber the lowest allowed sequence number when this object is constructed, or after it
     *                                   is cleared
     * @param sequenceNumberCapacity     the number of sequence numbers permitted to exist in this data structure. E.g.
     *                                   if the lowest allowed sequence number is 100 and the capacity is 10, then
     *                                   values with a sequence number between 100 and 109 (inclusive) will be allowed,
     *                                   and any value with a sequence number outside that range will be rejected.
     * @param allowExpansion             if true, then instead of rejecting elements with a sequence number higher than
     *                                   the allowed by the current capacity, increase capacity and then insert the
     *                                   element. Does not expand if the sequence number is too low to fit in the
     *                                   current capacity.
     * @param getSequenceNumberFromKey   a method that extracts the sequence number from a key
     */
    @SuppressWarnings("unchecked")
    protected AbstractSequenceMap(
            final long initialFirstSequenceNumber,
            final int sequenceNumberCapacity,
            final boolean allowExpansion,
            @NonNull final ToLongFunction<K> getSequenceNumberFromKey) {

        this.initialFirstSequenceNumber = initialFirstSequenceNumber;
        this.sequenceNumberCapacity = sequenceNumberCapacity;
        this.allowExpansion = allowExpansion;
        this.getSequenceNumberFromKey = Objects.requireNonNull(getSequenceNumberFromKey);

        data = buildDataMap();
        keySets = new SequenceKeySet[sequenceNumberCapacity];

        for (long sequenceNumber = initialFirstSequenceNumber;
                sequenceNumber < initialFirstSequenceNumber + sequenceNumberCapacity;
                sequenceNumber++) {

            keySets[getSequenceKeyIndex(sequenceNumber)] = new SequenceKeySet<>(sequenceNumber);
        }
    }

    /**
     * Set the smallest allowed sequence number in the current window.
     *
     * @param firstSequenceNumberInWindow the new first sequence number in the window
     */
    protected abstract void setFirstSequenceNumberInWindow(final long firstSequenceNumberInWindow);

    /**
     * Build the map to hold data.
     *
     * @return a map with appropriate thread safety guarantees
     */
    protected abstract Map<K, V> buildDataMap();

    /**
     * Acquire a lock on window management. Held during purge/expand calls, and during clear. No-op for implementations
     * that do not require thread safety.
     */
    protected void windowLock() {
        // Override if thread safety is required
    }

    /**
     * Release a lock on window management. Held during purge/expand calls, and during clear. No-op for implementations
     * that do not require thread safety.
     */
    protected void windowUnlock() {
        // Override if thread safety is required
    }

    /**
     * Acquire an exclusive lock on a sequence number. No-op for implementations that do not require thread safety.
     *
     * @param sequenceNumber the sequence number to lock
     */
    protected void lockSequenceNumber(final long sequenceNumber) {
        // Override if thread safety is required
    }

    /**
     * Release an exclusive lock on a sequence number. No-op for implementations that do not require thread safety.
     *
     * @param sequenceNumber the sequence number to unlock
     */
    protected void unlockSequenceNumber(final long sequenceNumber) {
        // Override if thread safety is required
    }

    /**
     * Acquire an exclusive lock on all sequence numbers. No-op for implementations that do not require thread safety.
     */
    protected void fullLock() {
        // Override if thread safety is required
    }

    /**
     * Release an exclusive lock on all sequence numbers. No-op for implementations that do not require thread safety.
     */
    protected void fullUnlock() {
        // Override if thread safety is required
    }

    /**
     * When the window is shifted significantly, it can be more efficient to grab all locks at the start, as compared to
     * locking on each sequence number one at a time. This method describes the size of the shift required to trigger a
     * full lock.
     *
     * @return shifts greater or equal to this in size will trigger a full lock
     */
    protected int getFullLockThreshold() {
        // Override if locking is needed
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public V get(final K key) {
        return data.get(key);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean containsKey(final K key) {
        return data.containsKey(key);
    }

    @Override
    public int getSequenceNumberCapacity() {
        return sequenceNumberCapacity;
    }

    /**
     * Get the sequence number from a key.
     *
     * @param key the key
     * @return the associated sequence number
     */
    private long getSequenceNumber(final K key) {
        return getSequenceNumberFromKey.applyAsLong(key);
    }

    /**
     * Get the key set index for a given sequence number and current capacity.
     *
     * @param sequenceNumber the sequence number in question
     * @return the index of the sequence number
     */
    private int getSequenceKeyIndex(final long sequenceNumber) {
        if (sequenceNumber >= 0) {
            return (int) (sequenceNumber % sequenceNumberCapacity);
        }
        return (int) (((sequenceNumber % sequenceNumberCapacity) + sequenceNumberCapacity) % sequenceNumberCapacity);
    }

    /**
     * Get the sequence key set for a given sequence number.
     *
     * @param sequenceNumber the sequence number to fetch
     * @return the key set for the sequence number
     */
    private SequenceKeySet<K> getSequenceKeySet(final long sequenceNumber) {
        return keySets[getSequenceKeyIndex(sequenceNumber)];
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public V computeIfAbsent(final K key, final Function<? super K, ? extends V> mappingFunction) {
        V value = data.get(key);

        if (value == null) {
            value = mappingFunction.apply(key);
            final boolean added = putIfAbsent(key, value);
            if (!added) {
                value = null;
            }
        }
        return value;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean putIfAbsent(final K key, final V value) {
        final long sequenceNumber = getSequenceNumber(key);
        SequenceKeySet<K> keys = getSequenceKeySet(sequenceNumber);

        lockSequenceNumber(sequenceNumber);
        try {
            if (keys.getSequenceNumber() != sequenceNumber) {
                // the key is outside the allowed window
                if (allowExpansion && sequenceNumber > getFirstSequenceNumberInWindow()) {
                    expandCapacity(sequenceNumber);
                    keys = getSequenceKeySet(sequenceNumber);
                } else {
                    return false;
                }
            }
            if (data.containsKey(key)) {
                // don't re-insert if the value is already present
                return false;
            }

            data.put(key, value);
            keys.getKeys().add(key);

            return true;
        } finally {
            unlockSequenceNumber(sequenceNumber);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public V put(final K key, final V value) {
        final long sequenceNumber = getSequenceNumber(key);
        SequenceKeySet<K> keys = getSequenceKeySet(sequenceNumber);

        lockSequenceNumber(sequenceNumber);
        try {
            if (keys.getSequenceNumber() != sequenceNumber) {
                // the key is outside the allowed window
                if (allowExpansion && sequenceNumber > getFirstSequenceNumberInWindow()) {
                    expandCapacity(sequenceNumber);
                    keys = getSequenceKeySet(sequenceNumber);
                } else {
                    return null;
                }
            }

            final V previousValue = data.put(key, value);
            keys.getKeys().add(key);

            return previousValue;
        } finally {
            unlockSequenceNumber(sequenceNumber);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public V remove(final K key) {
        final long sequenceNumber = getSequenceNumber(key);
        final SequenceKeySet<K> keys = getSequenceKeySet(sequenceNumber);

        lockSequenceNumber(sequenceNumber);
        try {
            if (keys.getSequenceNumber() != sequenceNumber) {
                // the key is outside the allowed window
                return null;
            }

            keys.getKeys().remove(key);
            return data.remove(key);

        } finally {
            unlockSequenceNumber(sequenceNumber);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void shiftWindow(final long firstSequenceNumberInWindow, final BiConsumer<K, V> removedValueHandler) {
        windowLock();

        final long shiftSize = firstSequenceNumberInWindow - getFirstSequenceNumberInWindow();
        final boolean largeShift = shiftSize >= getFullLockThreshold();

        if (largeShift) {
            // Better to take locks once than to take them over and over
            fullLock();
        }

        try {
            final long previousFirstSequenceNumber = getFirstSequenceNumberInWindow();
            if (firstSequenceNumberInWindow < previousFirstSequenceNumber) {
                throw new IllegalStateException(
                        "Window can only be shifted towards larger value. Current lowest sequence number = "
                                + previousFirstSequenceNumber + ", requested lowest sequence number = "
                                + firstSequenceNumberInWindow);
            }
            setFirstSequenceNumberInWindow(firstSequenceNumberInWindow);

            for (int offset = 0; offset < sequenceNumberCapacity; offset++) {

                // Stop purging once we encounter a high enough sequence number
                final long sequenceNumberToReplace = previousFirstSequenceNumber + offset;
                if (sequenceNumberToReplace >= firstSequenceNumberInWindow) {
                    return;
                }

                final SequenceKeySet<K> keys = getSequenceKeySet(sequenceNumberToReplace);

                if (!largeShift) {
                    lockSequenceNumber(sequenceNumberToReplace);
                }
                try {
                    // Remove the old data from the map
                    for (final K key : keys.getKeys()) {
                        final V value = data.remove(key);
                        if (removedValueHandler != null) {
                            removedValueHandler.accept(key, value);
                        }
                    }

                    // Prepare the key set for the new data
                    keys.setSequenceNumber(
                            mapToNewSequenceNumber(firstSequenceNumberInWindow, sequenceNumberToReplace));
                    keys.getKeys().clear();
                } finally {
                    if (!largeShift) {
                        unlockSequenceNumber(sequenceNumberToReplace);
                    }
                }
            }
        } finally {
            windowUnlock();
            if (largeShift) {
                fullUnlock();
            }
        }
    }

    /**
     * When the window is shifted, it causes some key sets in the circular buffer increase their sequence number. This
     * method computes the new sequence number that the key set is required to have.
     */
    private long mapToNewSequenceNumber(final long firstSequenceNumberInWindow, final long sequenceNumberToReplace) {
        // the distance between the new first sequence number in the window
        // and the sequence number that is being replaced
        final long difference = firstSequenceNumberInWindow - sequenceNumberToReplace;

        // The number of times we have wrapped around the buffer by increasing to the
        // new first sequence number. Will be 1 if we are increasing the minimum
        // sequence number by a small amount, may be larger than 1 if we suddenly
        // move the window a large distance in a single step.
        final long wrapFactor =
                difference / getSequenceNumberCapacity() + (difference % getSequenceNumberCapacity() == 0 ? 0 : 1);

        // Every time we go one time around the buffer, the sequence number at a particular
        // index increases by an amount equal to the capacity.
        final long increase = wrapFactor * getSequenceNumberCapacity();
        return sequenceNumberToReplace + increase;
    }

    /**
     * Expand the capacity so that we fit the required sequence number.
     *
     * @param requiredSequenceNumber the sequence number that we need to fit into this structure
     */
    @SuppressWarnings("unchecked")
    private void expandCapacity(final long requiredSequenceNumber) {
        windowLock();
        fullLock();
        try {
            final int oldCapacity = keySets.length;
            final long firstSequenceNumber = getFirstSequenceNumberInWindow();
            final long minimumCapacity = requiredSequenceNumber - firstSequenceNumber;
            if (minimumCapacity < 0) {
                // this can only happen if we get integer overflow
                throw new IllegalStateException("Cannot expand capacity beyond " + MAX_ARRAY_SIZE);
            } else if (minimumCapacity < MAX_ARRAY_SIZE / 2 - 1) {
                sequenceNumberCapacity = (int) (minimumCapacity * 2);
            } else if (minimumCapacity <= MAX_ARRAY_SIZE) {
                sequenceNumberCapacity = MAX_ARRAY_SIZE;
            } else {
                throw new IllegalStateException("Cannot expand capacity beyond " + MAX_ARRAY_SIZE);
            }

            final SequenceKeySet<K>[] oldKeySets = keySets;
            keySets = new SequenceKeySet[sequenceNumberCapacity];

            // Copy the old key sets into the new array
            for (int oldIndex = 0; oldIndex < oldCapacity; oldIndex++) {
                final long sequenceNumber = oldKeySets[oldIndex].getSequenceNumber();
                final int newIndex = getSequenceKeyIndex(sequenceNumber);
                keySets[newIndex] = oldKeySets[oldIndex];
            }

            // Create new key sets for the added capacity
            for (int offset = 0; offset < (sequenceNumberCapacity - oldCapacity); offset++) {
                final long newSequenceNumber = firstSequenceNumber + oldCapacity + offset;
                final int index = getSequenceKeyIndex(newSequenceNumber);
                keySets[index] = new SequenceKeySet<>(newSequenceNumber);
            }
        } finally {
            fullUnlock();
            windowUnlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeValuesWithSequenceNumber(final long sequenceNumber, final BiConsumer<K, V> removedValueHandler) {
        windowLock();
        try {
            final SequenceKeySet<K> keys = getSequenceKeySet(sequenceNumber);
            lockSequenceNumber(sequenceNumber);
            try {
                if (keys.getSequenceNumber() != sequenceNumber) {
                    return;
                }

                for (final K key : keys.getKeys()) {
                    final V value = data.remove(key);
                    if (removedValueHandler != null) {
                        removedValueHandler.accept(key, value);
                    }
                }
                keys.getKeys().clear();
            } finally {
                unlockSequenceNumber(sequenceNumber);
            }
        } finally {
            windowUnlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<K> getKeysWithSequenceNumber(final long sequenceNumber) {
        final List<K> list = new LinkedList<>();
        final SequenceKeySet<K> keys = getSequenceKeySet(sequenceNumber);

        lockSequenceNumber(sequenceNumber);
        try {
            if (keys.getSequenceNumber() == sequenceNumber) {
                list.addAll(keys.getKeys());
            }
        } finally {
            unlockSequenceNumber(sequenceNumber);
        }

        return list;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Map.Entry<K, V>> getEntriesWithSequenceNumber(final long sequenceNumber) {
        final List<Map.Entry<K, V>> list = new LinkedList<>();
        final SequenceKeySet<K> keys = getSequenceKeySet(sequenceNumber);

        lockSequenceNumber(sequenceNumber);
        try {
            if (keys.getSequenceNumber() == sequenceNumber) {
                for (final K key : keys.getKeys()) {
                    list.add(new AbstractMap.SimpleEntry<>(key, data.get(key)));
                }
            }
        } finally {
            unlockSequenceNumber(sequenceNumber);
        }

        return list;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getSize() {
        return data.size();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        windowLock();
        fullLock();

        try {
            data.clear();
            setFirstSequenceNumberInWindow(initialFirstSequenceNumber);
            for (int offset = 0; offset < sequenceNumberCapacity; offset++) {
                final long sequenceNumber = initialFirstSequenceNumber + offset;
                final SequenceKeySet<K> keys = getSequenceKeySet(sequenceNumber);
                keys.setSequenceNumber(sequenceNumber);
                keys.getKeys().clear();
            }
        } finally {
            windowUnlock();
            fullUnlock();
        }
    }

    /**
     * A set of keys with a particular sequence number. This object is designed to be reused as the allowable
     * window of sequence numbers shifts.
     *
     * @param <K>
     * 		the type of the key
     */
    private static class SequenceKeySet<K> {

        private long sequenceNumber;
        private final Set<K> keys = new HashSet<>();

        /**
         * Create an object capable of holding keys for a sequence number.
         *
         * @param sequenceNumber
         * 		the initial sequence number to be stored in this object
         */
        private SequenceKeySet(final long sequenceNumber) {
            this.sequenceNumber = sequenceNumber;
        }

        /**
         * Get the sequence number currently stored in this object.
         *
         * @return the current sequence number
         */
        private long getSequenceNumber() {
            return sequenceNumber;
        }

        /**
         * Set the sequence number currently stored in this object.
         *
         * @param sequenceNumber
         * 		the sequence number stored in this object
         */
        private void setSequenceNumber(final long sequenceNumber) {
            this.sequenceNumber = sequenceNumber;
        }

        /**
         * Get the set of keys contained by this object.
         *
         * @return a set of keys
         */
        private Set<K> getKeys() {
            return keys;
        }

        @Override
        public String toString() {
            return new ToStringBuilder(this)
                    .append("sequence number", sequenceNumber)
                    .append("size", keys.size())
                    .toString();
        }
    }
}
