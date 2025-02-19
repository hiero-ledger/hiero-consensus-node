/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
