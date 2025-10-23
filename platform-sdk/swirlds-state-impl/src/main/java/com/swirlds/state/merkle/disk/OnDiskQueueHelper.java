// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.disk;

import static com.swirlds.state.merkle.StateUtils.getStateKeyForQueue;
import static com.swirlds.state.merkle.StateUtils.getStateKeyForSingleton;
import static com.swirlds.state.merkle.StateUtils.getStateValueForQueue;
import static com.swirlds.state.merkle.StateUtils.getStateValueForQueueState;
import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.merkle.StateUtils;
import com.swirlds.state.merkle.StateValue;
import com.swirlds.state.merkle.StateValue.StateValueCodec;
import com.swirlds.state.merkle.disk.QueueState.QueueStateCodec;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * A helper class for managing an on-disk queue using a {@link VirtualMap} as the core storage mechanism.
 *
 * <p>This class was created to extract repetitive code from
 * {@link OnDiskWritableQueueState} and {@link OnDiskReadableQueueState}.
 *
 * <p><b>Why is it needed?</b></p>
 * To avoid duplication and simplify how Queue State classes interact with the underlying
 * {@link VirtualMap} storage.
 *
 * <p><b>What does it do?</b></p>
 * This class is responsible for:
 * <ul>
 *     <li>Providing functionality to iterate over elements stored in a queue-like manner.</li>
 *     <li>Retrieving elements using specific indices.</li>
 *     <li>Managing and updating metadata for the queue state, including head and tail pointers.</li>
 *     <li>Ensuring efficient interaction with the underlying {@link VirtualMap} storage and handling
 *         encoding/decoding operations with {@link Codec}.</li>
 * </ul>
 *
 * <p><b>Where is it used?</b></p>
 * It is used in {@link OnDiskWritableQueueState} and {@link OnDiskReadableQueueState} to perform
 * operations like adding, removing, or reading queue elements while ensuring persistence and
 * consistency across multiple layers of the queue implementation.
 *
 * @param <V> the type of elements stored in the on-disk queue
 */
public final class OnDiskQueueHelper<V> {

    /**
     * StateValue codec to store the queue state singleton.
     */
    public static final StateValueCodec<QueueState> QUEUE_STATE_VALUE_CODEC =
            new StateValueCodec<>(StateUtils.STATE_VALUE_QUEUE_STATE, QueueStateCodec.INSTANCE);

    /**
     * The unique state ID for this queue.
     */
    private final int stateId;

    /**
     * StateValue codec to store queue elements.
     */
    @NonNull
    private final Codec<StateValue<V>> stateValueCodec;

    /**
     * The core storage mechanism for the queue data within the on-disk queue.
     */
    @NonNull
    private final VirtualMap virtualMap;

    /**
     * Creates an instance of the on-disk queue helper.
     *
     * @param stateId The unique ID for this queue state
     * @param valueCodec The queue value codec
     * @param virtualMap The storage mechanism for the queue's data.
     */
    public OnDiskQueueHelper(
            final int stateId, @NonNull final Codec<V> valueCodec, @NonNull final VirtualMap virtualMap) {
        this.stateId = stateId;
        this.stateValueCodec = new StateValueCodec<>(stateId, requireNonNull(valueCodec));
        this.virtualMap = requireNonNull(virtualMap);
    }

    /**
     * Creates an iterator to traverse the elements in the queue's data source.
     *
     * @return An iterator for the elements of the queue.
     */
    @NonNull
    public Iterator<V> iterateOnDataSource(final long head, final long tail) {
        return new QueueIterator(head, tail);
    }

    /**
     * Retrieves an element from the queue's data store by its index.
     *
     * @param index The index of the element to retrieve.
     * @return The element at the specified index.
     * @throws IllegalStateException If the element is not found in the store.
     */
    @NonNull
    public V getFromStore(final long index) {
        final Bytes stateKey = StateUtils.getStateKeyForQueue(stateId, index);
        final StateValue<V> stateValue = virtualMap.get(stateKey, stateValueCodec);
        final V value = stateValue != null ? stateValue.value() : null;
        if (value == null) {
            throw new IllegalStateException("Can't find queue element at index " + index + " in the store");
        }
        return value;
    }

    public void addToStore(final long tail, final V value) {
        final Bytes stateKey = getStateKeyForQueue(stateId, tail);
        final StateValue<V> stateValue = getStateValueForQueue(stateId, value);
        virtualMap.put(stateKey, stateValue, stateValueCodec);
    }

    @Nullable
    public V removeFromStore(final long head) {
        final Bytes stateKey = getStateKeyForQueue(stateId, head);
        final StateValue<V> stateValue = virtualMap.remove(stateKey, stateValueCodec);
        return stateValue != null ? stateValue.value() : null;
    }

    /**
     * Retrieves the current state of the queue.
     *
     * @return The current state of the queue.
     */
    public QueueState getState() {
        final Bytes queueStateKey = getStateKeyForSingleton(stateId);
        final StateValue<QueueState> queueStateValue = virtualMap.get(queueStateKey, QUEUE_STATE_VALUE_CODEC);
        return queueStateValue != null ? queueStateValue.value() : null;
    }

    /**
     * Updates the state of the queue in the data store.
     *
     * @param state The new state to set for the queue.
     */
    public void updateState(@NonNull final QueueState state) {
        final Bytes keyBytes = getStateKeyForSingleton(stateId);
        final StateValue<QueueState> queueStateValue = getStateValueForQueueState(state);
        virtualMap.put(keyBytes, queueStateValue, QUEUE_STATE_VALUE_CODEC);
    }

    /**
     * Checks if a queue is empty.
     *
     * @param state the queue state to check
     * @return {@code true} if the queue is empty, {@code false} otherwise
     */
    public static boolean isEmpty(@NonNull final QueueState state) {
        return state.head() == state.tail();
    }

    /**
     * Utility class for iterating over queue elements within a specific range.
     */
    private class QueueIterator implements Iterator<V> {

        /**
         * The starting position of the iteration (inclusive).
         */
        private final long start;

        /**
         * The ending position of the iteration (exclusive).
         */
        private final long limit;

        /**
         * The current position of the iterator, where {@code start <= current < limit}.
         */
        private long current;

        /**
         * Creates a new iterator for the specified range.
         *
         * @param start The starting position of the iteration (inclusive).
         * @param limit The ending position of the iteration (exclusive).
         */
        public QueueIterator(final long start, final long limit) {
            this.start = start;
            this.limit = limit;
            reset();
        }

        /**
         * Checks if there are more elements to iterate over.
         *
         * @return {@code true} if there are more elements, {@code false} otherwise.
         */
        @Override
        public boolean hasNext() {
            return current < limit;
        }

        /**
         * Returns the next element in the iteration.
         *
         * @return The next element in the queue.
         * @throws NoSuchElementException If no more elements are available.
         * @throws ConcurrentModificationException If the queue was modified during iteration.
         */
        @Override
        public V next() {
            if (current == limit) {
                throw new NoSuchElementException();
            }
            try {
                return getFromStore(current++);
            } catch (final IllegalStateException e) {
                throw new ConcurrentModificationException(e);
            }
        }

        /**
         * Resets the iterator to the starting position.
         */
        void reset() {
            current = start;
        }
    }
}
