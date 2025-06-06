// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state;

import static java.util.Objects.requireNonNull;

import com.swirlds.state.spi.ReadableQueueState;
import com.swirlds.state.spi.WritableQueueState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Iterator;

/**
 * A wrapper around a {@link WritableQueueState} that provides read-only access to a given
 * {@link WritableQueueState} delegate.
 *
 * @param <E> The type of the elements in the queue
 */
public class ReadonlyQueueStateWrapper<E> implements ReadableQueueState<E> {
    private final WritableQueueState<E> delegate;

    /**
     * Create a new wrapper around the given {@code delegate}.
     *
     * @param delegate the {@link WritableQueueState} to wrap
     */
    public ReadonlyQueueStateWrapper(@NonNull final WritableQueueState<E> delegate) {
        this.delegate = requireNonNull(delegate, "delegate must not be null");
    }

    @NonNull
    @Override
    public String getServiceName() {
        return delegate.getServiceName();
    }

    @NonNull
    @Override
    public String getStateKey() {
        return delegate.getStateKey();
    }

    @Nullable
    @Override
    public E peek() {
        return delegate.peek();
    }

    @NonNull
    @Override
    public Iterator<E> iterator() {
        return delegate.iterator();
    }
}
