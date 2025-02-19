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

package com.swirlds.state.merkle.disk;

import static com.swirlds.state.merkle.logging.StateLogger.logMapGet;
import static com.swirlds.state.merkle.logging.StateLogger.logMapGetSize;
import static com.swirlds.state.merkle.logging.StateLogger.logMapIterate;
import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.Codec;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableKVStateBase;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;

/**
 * An implementation of {@link ReadableKVState} backed by a {@link VirtualMap}, resulting in a state
 * that is stored on disk.
 *
 * @param <K> The type of key for the state
 * @param <V> The type of value for the state
 */
public final class OnDiskReadableKVState<K, V> extends ReadableKVStateBase<K, V> {

    /** The backing merkle data structure to use */
    private final VirtualMap virtualMap;

    @NonNull
    private final Codec<K> keyCodec;

    @NonNull
    private final Codec<V> valueCodec;

    /**
     * Create a new instance
     *
     * @param stateKey
     * @param keyCodec
     * @param valueCodec
     * @param virtualMap the backing merkle structure to use
     */
    public OnDiskReadableKVState(
            String stateKey,
            @NonNull final Codec<K> keyCodec,
            @NonNull final Codec<V> valueCodec,
            @NonNull final VirtualMap virtualMap) {
        super(stateKey);
        this.keyCodec = requireNonNull(keyCodec);
        this.valueCodec = requireNonNull(valueCodec);
        this.virtualMap = requireNonNull(virtualMap);
    }

    /** {@inheritDoc} */
    @Override
    protected V readFromDataSource(@NonNull K key) {
        final var kb = keyCodec.toBytes(key);
        final var value = virtualMap.get(kb, valueCodec);
        // Log to transaction state log, what was read
        logMapGet(getStateKey(), key, value);
        return value;
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    protected Iterator<K> iterateFromDataSource() {
        // Log to transaction state log, what was iterated
        logMapIterate(getStateKey(), virtualMap, keyCodec);
        return new OnDiskIterator<>(virtualMap, keyCodec);
    }

    /** {@inheritDoc} */
    @Override
    @Deprecated
    public long size() {
        final var size = virtualMap.size();
        // Log to transaction state log, size of map
        logMapGetSize(getStateKey(), size);
        return size;
    }

    @Override
    public void warm(@NonNull final K key) {
        final var kb = keyCodec.toBytes(key);
        virtualMap.warm(kb);
    }
}
