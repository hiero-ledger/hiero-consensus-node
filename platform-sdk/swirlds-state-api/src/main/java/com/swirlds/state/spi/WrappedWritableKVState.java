// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;
import java.util.Objects;

/**
 * An implementation of {@link WritableKVState} that delegates to another {@link WritableKVState} as
 * though it were the backend data source. Modifications to this {@link WrappedWritableKVState} are
 * buffered, along with reads, allowing code to rollback by simply throwing away the wrapper.
 *
 * @param <K> The key
 * @param <V> The value
 */
public class WrappedWritableKVState<K, V> extends WritableKVStateBase<K, V> {
    /**
     * The {@link WritableKVState} to delegate to for all read operations on cache miss, and for
     * committing changes
     */
    private final WritableKVState<K, V> delegate;

    /**
     * Create a new instance that will treat the given {@code delegate} as the backend data source.
     * Note that the lifecycle of the delegate <b>MUST</b> be as long as, or longer than, the
     * lifecycle of this instance. If the delegate is reset or decommissioned while being used as a
     * delegate, bugs will occur.
     *
     * @param delegate The delegate. Must not be null.
     */
    public WrappedWritableKVState(@NonNull final WritableKVState<K, V> delegate) {
        super(delegate.getServiceName(), delegate.getStateKey());
        this.delegate = Objects.requireNonNull(delegate);
    }

    /** {@inheritDoc} */
    @Override
    protected void putIntoDataSource(@NonNull K key, @NonNull V value) {
        delegate.put(key, value);
    }

    /** {@inheritDoc} */
    @Override
    protected void removeFromDataSource(@NonNull K key) {
        delegate.remove(key);
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
    public long sizeOfDataSource() {
        return delegate.size();
    }
}
