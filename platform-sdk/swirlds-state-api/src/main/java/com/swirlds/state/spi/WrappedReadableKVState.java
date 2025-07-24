// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;

/**
 * Used to wrap a {@link ReadableKVState}, allowing to buffer reads into the {@link
 * WrappedReadableKVState}.
 *
 * @param <K> The key of the state
 * @param <V> The value of the state
 */
public class WrappedReadableKVState<K, V> extends ReadableKVStateBase<K, V> {
    /** The {@link ReadableKVState} to delegate to for all read operations on cache miss */
    private final ReadableKVState<K, V> delegate;

    /**
     * Create a new instance that will treat the given {@code delegate} as the backend data source.
     * Note that the lifecycle of the delegate <b>MUST</b> be as long as, or longer than, the
     * lifecycle of this instance. If the delegate is reset or decommissioned while being used as a
     * delegate, bugs will occur.
     *
     * @param delegate The delegate. Must not be null.
     */
    public WrappedReadableKVState(@NonNull final ReadableKVState<K, V> delegate) {
        super(delegate.getServiceName(), delegate.getStateKey());
        this.delegate = delegate;
    }

    /** {@inheritDoc} */
    @Override
    protected V readFromDataSource(@NonNull K key) {
        return delegate.get(key);
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    protected Iterator<K> iterateFromDataSource() {
        return delegate.keys();
    }

    /** {@inheritDoc} */
    @Override
    @Deprecated
    public long size() {
        return delegate.size();
    }
}
