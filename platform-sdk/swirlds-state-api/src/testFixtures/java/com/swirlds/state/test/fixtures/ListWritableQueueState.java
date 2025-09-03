// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.test.fixtures;

import static java.util.Objects.requireNonNull;

import com.swirlds.state.spi.WritableQueueStateBase;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Queue;

/** Useful class for testing {@link WritableQueueStateBase} */
public class ListWritableQueueState<E> extends WritableQueueStateBase<E> {

    /** Represents the backing storage for this state */
    private final Queue<E> backingStore;

    /**
     * Create an instance using the given Queue as the backing store. This is useful when you want to
     * pre-populate the queue, or if you want to use Mockito to mock it or cause it to throw
     * exceptions when certain keys are accessed, etc.
     *
     * @param stateId The state ID
     * @param label The service label
     * @param backingStore The backing store to use
     */
    public ListWritableQueueState(
            final int stateId, @NonNull final String label, @NonNull final Queue<E> backingStore) {
        super(stateId, requireNonNull(label));
        this.backingStore = requireNonNull(backingStore);
    }

    @Override
    protected void addToDataSource(@NonNull E element) {
        backingStore.add(element);
    }

    @Override
    protected void removeFromDataSource() {
        backingStore.remove();
    }

    @NonNull
    @Override
    protected Iterator<E> iterateOnDataSource() {
        return backingStore.iterator();
    }

    /**
     * Create a new {@link ListWritableQueueState.Builder} for building a {@link ListWritableQueueState}. The builder has
     * convenience methods for pre-populating the queue.
     *
     * @param <E>         The element type
     * @param stateId     The state ID
     * @param label       The service label
     * @return A {@link ListWritableQueueState.Builder} to be used for creating a {@link ListWritableQueueState}.
     */
    @NonNull
    public static <E> ListWritableQueueState.Builder<E> builder(final int stateId, @NonNull final String label) {
        return new ListWritableQueueState.Builder<>(stateId, label);
    }

    /**
     * A convenient builder for creating instances of {@link ListWritableQueueState}.
     */
    public static final class Builder<E> {

        private final int stateId;
        private final String label;
        private final Queue<E> backingStore = new LinkedList<>();

        Builder(final int stateId, @NonNull final String label) {
            this.stateId = stateId;
            this.label = requireNonNull(label);
        }

        /**
         * Add an element to the state's backing map. This is used to pre-initialize the backing map. The created state
         * will be "clean" with no modifications.
         *
         * @param element The element
         * @return a reference to this builder
         */
        @NonNull
        public ListWritableQueueState.Builder<E> value(@NonNull E element) {
            backingStore.add(element);
            return this;
        }

        /**
         * Builds the state.
         *
         * @return an instance of the state, preloaded with whatever elements were defined.
         */
        @NonNull
        public ListWritableQueueState<E> build() {
            return new ListWritableQueueState<>(stateId, label, new LinkedList<>(backingStore));
        }
    }
}
