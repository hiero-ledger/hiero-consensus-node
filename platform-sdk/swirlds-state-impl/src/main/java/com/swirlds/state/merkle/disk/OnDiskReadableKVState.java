// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.disk;

import static com.swirlds.state.merkle.StateUtils.getStateKeyForKv;
import static com.swirlds.state.merkle.logging.StateLogger.logMapGet;
import static com.swirlds.state.merkle.logging.StateLogger.logMapGetSize;
import static com.swirlds.state.merkle.logging.StateLogger.logMapIterate;
import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.merkle.StateValue;
import com.swirlds.state.merkle.StateValue.StateValueCodec;
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
    @NonNull
    private final VirtualMap virtualMap;

    @NonNull
    private final Codec<K> keyCodec;

    @NonNull
    private final Codec<StateValue<V>> stateValueCodec;

    /**
     * Create a new instance
     *
     * @param stateId     the state ID
     * @param label       the state label
     * @param keyCodec    the codec for the key
     * @param virtualMap  the backing merkle data structure to use
     */
    public OnDiskReadableKVState(
            final int stateId,
            @NonNull final String label,
            @NonNull final Codec<K> keyCodec,
            @NonNull final Codec<V> valueCodec,
            @NonNull final VirtualMap virtualMap) {
        super(stateId, requireNonNull(label));
        this.keyCodec = requireNonNull(keyCodec);
        this.stateValueCodec = new StateValueCodec<>(stateId, requireNonNull(valueCodec));
        this.virtualMap = requireNonNull(virtualMap);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected V readFromDataSource(@NonNull K key) {
        final Bytes stateKey = getStateKeyForKv(stateId, key, keyCodec);
        final StateValue<V> stateValue = virtualMap.get(stateKey, stateValueCodec);
        final V value = stateValue != null ? stateValue.value() : null;
        // Log to transaction state log, what was read
        logMapGet(label, key, value);
        return value;
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    protected Iterator<K> iterateFromDataSource() {
        // Log to transaction state log, what was iterated
        logMapIterate(label, virtualMap, keyCodec);
        return new OnDiskIterator<>(virtualMap, keyCodec, stateId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Deprecated
    public long size() {
        final var size = virtualMap.size();
        // Log to transaction state log, size of map
        logMapGetSize(label, size);
        return size;
    }

    @Override
    public void warm(@NonNull final K key) {
        final Bytes stateKey = getStateKeyForKv(stateId, key, keyCodec);
        virtualMap.warm(stateKey);
    }
}
