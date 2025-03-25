// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state;

import static java.util.Objects.requireNonNull;

import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.WritableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Iterator;
import java.util.Set;

/**
 * A wrapper around a {@link WritableKVState} that provides read-only access to a given
 * {@link WritableKVState} delegate.
 *
 * @param <K> The type of the key
 * @param <V> The type of the value
 */
public class ReadonlyKVStateWrapper<K, V> implements ReadableKVState<K, V> {
    private final WritableKVState<K, V> delegate;

    /**
     * Create a new wrapper around the given {@code delegate}.
     *
     * @param delegate the {@link WritableKVState} to wrap
     */
    public ReadonlyKVStateWrapper(@NonNull final WritableKVState<K, V> delegate) {
        this.delegate = requireNonNull(delegate, "delegate must not be null");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public final String getServiceName() {
        return delegate.getServiceName();
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public String getStateKey() {
        return delegate.getStateKey();
    }

    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public V get(@NonNull K key) {
        return delegate.get(key);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public Iterator<K> keys() {
        return delegate.keys();
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public Set<K> readKeys() {
        return delegate.readKeys();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Deprecated
    public long size() {
        return delegate.size();
    }
}
