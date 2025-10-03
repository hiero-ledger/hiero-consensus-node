// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.test.fixtures.merkle.disk;

import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableKVStateBase;
import com.swirlds.state.test.fixtures.merkle.MerkleStateRoot;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;

/**
 * An implementation of {@link WritableKVState} backed by a {@link VirtualMap}, resulting in a state
 * that is stored on disk.
 * @deprecated This class should be removed together with {@link MerkleStateRoot}.
 * @param <K> The type of key for the state
 * @param <V> The type of value for the state
 */
@Deprecated
public final class BackedWritableKVState<K, V> extends WritableKVStateBase<K, V> {

    /** The backing merkle data structure */
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
    public BackedWritableKVState(
            final int stateId,
            @NonNull final String label,
            @NonNull final Codec<K> keyCodec,
            @NonNull final Codec<V> valueCodec,
            @NonNull final VirtualMap virtualMap) {
        super(stateId, requireNonNull(label));
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
    protected void putIntoDataSource(@NonNull K key, @NonNull V value) {
        final Bytes kb = keyCodec.toBytes(key);
        assert kb != null;
        virtualMap.put(kb, value, valueCodec);
    }

    /** {@inheritDoc} */
    @Override
    protected void removeFromDataSource(@NonNull K key) {
        final var k = keyCodec.toBytes(key);
        final var removed = virtualMap.remove(k, valueCodec);
    }

    /** {@inheritDoc} */
    @Override
    public long sizeOfDataSource() {
        return virtualMap.size();
    }
}
