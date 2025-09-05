// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * An implementation of {@link WritableSingletonState} that delegates to another {@link WritableSingletonState} as
 * though it were the backend data source. Modifications to this {@link WrappedWritableSingletonState} are
 * buffered, along with reads, allowing code to rollback by simply throwing away the wrapper.
 *
 * @param <T> the type of the state
 */
public class WrappedWritableSingletonState<T> extends WritableSingletonStateBase<T> {

    private final WritableSingletonState<T> delegate;

    /**
     * Create a new instance that will treat the given {@code delegate} as the backend data source.
     * Note that the lifecycle of the delegate <b>MUST</b> be as long as, or longer than, the
     * lifecycle of this instance. If the delegate is reset or decommissioned while being used as a
     * delegate, bugs will occur.
     *
     * @param delegate The delegate. Must not be null.
     * @throws NullPointerException if {@code delegate} is {@code null}
     */
    public WrappedWritableSingletonState(@NonNull final WritableSingletonState<T> delegate) {
        super(delegate.getStateId(), null);
        this.delegate = delegate;
    }

    @Override
    protected void putIntoDataSource(@NonNull T value) {
        delegate.put(value);
    }

    @Override
    protected void removeFromDataSource() {
        delegate.put(null);
    }

    @Override
    protected T readFromDataSource() {
        return delegate.get();
    }
}
