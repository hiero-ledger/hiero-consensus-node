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

    protected final String serviceName;

    protected final String stateKey;

    /** Create a new instance */
    protected ReadableQueueStateBase(@NonNull final String serviceName, @NonNull final String stateKey) {
        this.serviceName = Objects.requireNonNull(serviceName);
        this.stateKey = Objects.requireNonNull(stateKey);
    }

    @Override
    @NonNull
    public final String getServiceName() {
        return serviceName;
    }

    @Override
    @NonNull
    public final String getStateKey() {
        return stateKey;
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
