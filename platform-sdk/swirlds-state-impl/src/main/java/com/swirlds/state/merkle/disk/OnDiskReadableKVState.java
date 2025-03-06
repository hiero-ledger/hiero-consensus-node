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
import edu.umd.cs.findbugs.annotations.Nullable;
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
    private final VirtualMap<OnDiskKey<K>, OnDiskValue<V>> virtualMap;

    private final long keyClassId;
    private final Codec<K> keyCodec;

    /**
     * Create a new instance
     *
     * @param stateKey
     * @param keyClassId
     * @param keyCodec
     * @param virtualMap the backing merkle structure to use
     */
    public OnDiskReadableKVState(
            String stateKey,
            final long keyClassId,
            @Nullable final Codec<K> keyCodec,
            @NonNull final VirtualMap<OnDiskKey<K>, OnDiskValue<V>> virtualMap) {
        super(stateKey);
        this.keyClassId = keyClassId;
        this.keyCodec = keyCodec;
        this.virtualMap = requireNonNull(virtualMap);
    }

    /** {@inheritDoc} */
    @Override
    protected V readFromDataSource(@NonNull K key) {
        final var k = new OnDiskKey<>(keyClassId, keyCodec, key);
        final var v = virtualMap.get(k);
        final var value = v == null ? null : v.getValue();
        // Log to transaction state log, what was read
        logMapGet(getStateKey(), key, value);
        return value;
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    protected Iterator<K> iterateFromDataSource() {
        // Log to transaction state log, what was iterated
        logMapIterate(getStateKey(), virtualMap);
        return new OnDiskIterator<>(virtualMap);
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
        final var k = new OnDiskKey<>(keyClassId, keyCodec, key);
        virtualMap.warm(k);
    }
}
