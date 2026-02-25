// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.test.fixtures;

import com.swirlds.state.spi.ReadableQueueStateBase;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Objects;
import java.util.Queue;

/** Useful class for testing {@link ReadableQueueStateBase} */
public class ListReadableQueueState<E> extends ReadableQueueStateBase<E> {

    /** Represents the backing storage for this state */
    private final Queue<E> backingStore;

    /**
     * Create an instance using the given Queue as the backing store. This is useful when you want to
     * pre-populate the queue, or if you want to use Mockito to mock it or cause it to throw
     * exceptions when certain keys are accessed, etc.
     *
     * @param stateId      The state ID
     * @param label        The service label
     * @param backingStore The backing store to use
     */
    public ListReadableQueueState(final int stateId, final String label, @NonNull final Queue<E> backingStore) {
        super(stateId, label);
        this.backingStore = Objects.requireNonNull(backingStore);
    }

    @Nullable
    @Override
    protected E peekOnDataSource() {
        return backingStore.peek();
    }

    @NonNull
    @Override
    protected Iterator<E> iterateOnDataSource() {
        return backingStore.iterator();
    }

    /**
     * Create a new {@link ListReadableQueueState.Builder} for building a {@link ListReadableQueueState}. The builder has
     * convenience methods for pre-populating the queue.
     *
     * @param <E>      The value type
     * @param stateId  The state ID
     * @param label    The state label
     * @return A {@link ListReadableQueueState.Builder} to be used for creating a {@link ListReadableQueueState}.
     */
    @NonNull
    public static <E> ListReadableQueueState.Builder<E> builder(final int stateId, @NonNull final String label) {
        return new ListReadableQueueState.Builder<>(stateId, label);
    }

    /**
     * A convenient builder for creating instances of {@link ListReadableQueueState}.
     */
    public static final class Builder<E> {

        private final int stateId;
        private final String label;
        private final Queue<E> backingStore = new LinkedList<>();

        Builder(final int stateId, @NonNull final String label) {
            this.stateId = stateId;
            this.label = label;
        }

        /**
         * Add an element to the state's backing map. This is used to pre-initialize the backing map. The created state
         * will be "clean" with no modifications.
         *
         * @param element The element
         * @return a reference to this builder
         */
        @NonNull
        public ListReadableQueueState.Builder<E> value(@NonNull E element) {
            backingStore.add(element);
            return this;
        }

        /**
         * Builds the state.
         *
         * @return an instance of the state, preloaded with whatever elements were defined.
         */
        @NonNull
        public ListReadableQueueState<E> build() {
            return new ListReadableQueueState<>(stateId, label, new LinkedList<>(backingStore));
        }
    }
}
