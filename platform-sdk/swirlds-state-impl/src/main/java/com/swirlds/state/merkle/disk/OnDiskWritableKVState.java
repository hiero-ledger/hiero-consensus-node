// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.disk;

import static com.swirlds.state.merkle.StateUtils.getStateKeyForKv;
import static com.swirlds.state.merkle.StateUtils.getStateValueForKv;
import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.merkle.StateValue;
import com.swirlds.state.merkle.StateValue.StateValueCodec;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableKVStateBase;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
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
    @NonNull
    private final VirtualMap virtualMap;

    @NonNull
    private final Codec<K> keyCodec;

    @NonNull
    private final Codec<StateValue<V>> stateValueCodec;

    /**
     * Create a new instance
     *
     * @param label       the service label
     * @param stateId     the state ID
     * @param keyCodec    the codec for the key
     * @param virtualMap  the backing merkle data structure to use
     */
    public OnDiskWritableKVState(
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
        return stateValue != null ? stateValue.value() : null;
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    protected Iterator<K> iterateFromDataSource() {
        return new OnDiskIterator<>(virtualMap, keyCodec, stateId);
    }

    /** {@inheritDoc} */
    @Override
    protected void putIntoDataSource(@NonNull K key, @NonNull V value) {
        assert keyCodec.toBytes(key) != null;

        final Bytes keyBytes = getStateKeyForKv(stateId, key, keyCodec);
        final StateValue<V> stateValue = getStateValueForKv(stateId, value);

        virtualMap.put(keyBytes, stateValue, stateValueCodec);
    }

    /** {@inheritDoc} */
    @Override
    protected void removeFromDataSource(@NonNull K key) {
        final Bytes stateKey = getStateKeyForKv(stateId, key, keyCodec);
        final StateValue<V> stateValue = virtualMap.remove(stateKey, stateValueCodec);
        final var removedValue = stateValue != null ? stateValue.value() : null;
    }

    /** {@inheritDoc} */
    @Override
    public long sizeOfDataSource() {
        return virtualMap.size();
    }
}
