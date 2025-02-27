// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.disk;

import static com.swirlds.state.merkle.logging.StateLogger.logMapGet;
import static com.swirlds.state.merkle.logging.StateLogger.logMapGetSize;
import static com.swirlds.state.merkle.logging.StateLogger.logMapIterate;
import static com.swirlds.state.merkle.logging.StateLogger.logMapPut;
import static com.swirlds.state.merkle.logging.StateLogger.logMapRemove;
import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.Codec;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableKVStateBase;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Iterator;

/**
 * An implementation of {@link WritableKVState} backed by a {@link VirtualMap}, resulting in a state
 * that is stored on disk.
 *
 * @param <K> The type of key for the state
 * @param <V> The type of value for the state
 */
public final class OnDiskWritableKVState<K, V> extends WritableKVStateBase<K, V> {
    /** The backing merkle data structure */
    private final VirtualMap<OnDiskKey<K>, OnDiskValue<V>> virtualMap;

    private final Codec<K> keyCodec;
    private final long keyClassId;
    private final Codec<V> valueCodec;
    private final long valueClassId;

    /**
     * Create a new instance
     *
     * @param stateKey     the state key
     * @param keyClassId   the class ID for the key
     * @param keyCodec     the codec for the key
     * @param valueClassId the class ID for the value
     * @param valueCodec   the codec for the value
     * @param virtualMap   the backing merkle data structure to use
     */
    public OnDiskWritableKVState(
            String stateKey,
            final long keyClassId,
            @Nullable final Codec<K> keyCodec,
            final long valueClassId,
            @NonNull final Codec<V> valueCodec,
            @NonNull final VirtualMap<OnDiskKey<K>, OnDiskValue<V>> virtualMap) {
        super(stateKey);
        this.keyClassId = keyClassId;
        this.keyCodec = keyCodec;
        this.valueClassId = valueClassId;
        this.valueCodec = valueCodec;
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
    protected void putIntoDataSource(@NonNull K key, @NonNull V value) {
        final var k = new OnDiskKey<>(keyClassId, keyCodec, key);
        virtualMap.put(k, new OnDiskValue<>(valueClassId, valueCodec, value));
        // Log to transaction state log, what was put
        logMapPut(getStateKey(), key, value);
    }

    /** {@inheritDoc} */
    @Override
    protected void removeFromDataSource(@NonNull K key) {
        final var k = new OnDiskKey<>(keyClassId, keyCodec, key);
        final var removed = virtualMap.remove(k);
        // Log to transaction state log, what was removed
        logMapRemove(getStateKey(), key, removed);
    }

    /** {@inheritDoc} */
    @Override
    public long sizeOfDataSource() {
        final var size = virtualMap.size();
        // Log to transaction state log, size of map
        logMapGetSize(getStateKey(), size);
        return size;
    }

    @Override
    public void commit() {
        super.commit();
    }
}
