// SPDX-License-Identifier: Apache-2.0
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
public final class BackedReadableKVState<K, V> extends ReadableKVStateBase<K, V> {

    /** The backing merkle data structure to use */
    private final VirtualMap virtualMap;

    @NonNull
    private final Codec<K> keyCodec;

    @NonNull
    private final Codec<V> valueCodec;

    /**
     * Create a new instance
     *
     * @param serviceName
     * @param stateKey
     * @param keyCodec
     * @param valueCodec
     * @param virtualMap the backing merkle structure to use
     */
    public BackedReadableKVState(
            @NonNull final String serviceName,
            @NonNull final String stateKey,
            @NonNull final Codec<K> keyCodec,
            @NonNull final Codec<V> valueCodec,
            @NonNull final VirtualMap virtualMap) {
        super(serviceName, stateKey);
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
        return new BackedOnDiskIterator<>(virtualMap, keyCodec);
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
