// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;
import java.util.Objects;

/**
 * An implementation of {@link WritableQueueState} that delegates to another {@link WritableQueueState} as
 * though it were the backend data source. Modifications to this {@link WrappedWritableKVState} are
 * buffered, along with reads, allowing code to rollback by simply throwing away the wrapper.
 *
 * @param <E> The type of element in the queue.
 */
public class WrappedWritableQueueState<E> extends WritableQueueStateBase<E> {

    private WritableQueueState<E> delegate;

    /**
     * Create a new instance that will treat the given {@code delegate} as the backend data source.
     * Note that the lifecycle of the delegate <b>MUST</b> be as long as, or longer than, the
     * lifecycle of this instance. If the delegate is reset or decommissioned while being used as a
     * delegate, bugs will occur.
     *
     * @param delegate The delegate. Must not be null.
     */
    public WrappedWritableQueueState(@NonNull final WritableQueueState<E> delegate) {
        super(delegate.getStateId(), null);
        this.delegate = Objects.requireNonNull(delegate);
    }

    /**
     * Points this wrapper at a new backend and clears buffered reads and writes. Used when the
     * wrapper is reused across sequential handle dispatches.
     *
     * @param newDelegate the backend to read from and commit to
     */
    public void retarget(@NonNull final WritableQueueState<E> newDelegate) {
        this.delegate = Objects.requireNonNull(newDelegate);
        reset();
    }

    @Override
    protected void addToDataSource(@NonNull final E element) {
        delegate.add(element);
        if (delegate instanceof WritableQueueStateBase<E> backend) {
            backend.notifyQueuePush(element);
        }
    }

    @Override
    protected void removeFromDataSource() {
        delegate.poll();
        if (delegate instanceof WritableQueueStateBase<?> backend) {
            backend.notifyQueuePop();
        }
    }

    @NonNull
    @Override
    protected Iterator<E> iterateOnDataSource() {
        return delegate.iterator();
    }
}
