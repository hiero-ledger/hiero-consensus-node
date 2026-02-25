// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Iterator;
import java.util.Objects;

/**
 * Base implementation of the {@link ReadableQueueState}. Caches the peeked element.
 *
 * @param <E> The type of the elements in this queue
 */
public abstract class ReadableQueueStateBase<E> implements ReadableQueueState<E> {

    private E peekedElement;

    protected final String label;

    /** State label used in logs, typically serviceName.stateKey */
    protected final int stateId;

    /** Create a new instance */
    protected ReadableQueueStateBase(final int stateId, @NonNull final String label) {
        this.label = Objects.requireNonNull(label);
        this.stateId = stateId;
    }

    @Override
    public final int getStateId() {
        return stateId;
    }

    @Nullable
    @Override
    public E peek() {
        if (peekedElement == null) {
            peekedElement = peekOnDataSource();
        }
        return peekedElement;
    }

    @NonNull
    @Override
    public Iterator<E> iterator() {
        return iterateOnDataSource();
    }

    @Nullable
    protected abstract E peekOnDataSource();

    @NonNull
    protected abstract Iterator<E> iterateOnDataSource();
}
