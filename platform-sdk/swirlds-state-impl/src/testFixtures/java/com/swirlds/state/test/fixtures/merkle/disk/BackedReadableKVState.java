// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.test.fixtures.merkle.disk;

import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.Codec;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableKVStateBase;
import com.swirlds.state.test.fixtures.merkle.MerkleStateRoot;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;

/**
 * An implementation of {@link ReadableKVState} backed by a {@link VirtualMap}, resulting in a state
 * that is stored on disk.
 * @deprecated This class should be removed together with {@link MerkleStateRoot}.
 * @param <K> The type of key for the state
 * @param <V> The type of value for the state
 */
@Deprecated
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
     * @param stateId      the state ID
     * @param label        the service label
     * @param keyCodec     the codec for the key
     * @param valueCodec   the codec for the value
     * @param virtualMap   the backing merkle data structure to use
     */
    public BackedReadableKVState(
            final int stateId,
            @NonNull final String label,
            @NonNull final Codec<K> keyCodec,
            @NonNull final Codec<V> valueCodec,
            @NonNull final VirtualMap virtualMap) {
        super(stateId, label);
        this.keyCodec = requireNonNull(keyCodec);
        this.valueCodec = requireNonNull(valueCodec);
        this.virtualMap = requireNonNull(virtualMap);
    }

    /** {@inheritDoc} */
    @Override
    protected V readFromDataSource(@NonNull K key) {
        final var kb = keyCodec.toBytes(key);
        return virtualMap.get(kb, valueCodec);
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    protected Iterator<K> iterateFromDataSource() {
        return new BackedOnDiskIterator<>(virtualMap, keyCodec);
    }

    /** {@inheritDoc} */
    @Override
    @Deprecated
    public long size() {
        return virtualMap.size();
    }

    @Override
    public void warm(@NonNull final K key) {
        final var kb = keyCodec.toBytes(key);
        virtualMap.warm(kb);
    }
}
