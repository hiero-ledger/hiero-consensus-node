// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.memory;

import static com.swirlds.state.merkle.logging.StateLogger.logMapGet;
import static com.swirlds.state.merkle.logging.StateLogger.logMapGetSize;
import static com.swirlds.state.merkle.logging.StateLogger.logMapIterate;
import static com.swirlds.state.merkle.logging.StateLogger.logMapPut;
import static com.swirlds.state.merkle.logging.StateLogger.logMapRemove;

import com.hedera.pbj.runtime.Codec;
import com.swirlds.merkle.map.MerkleMap;
import com.swirlds.state.merkle.MerkleStateRoot;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableKVStateBase;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Iterator;
import java.util.Objects;

/**
 * An implementation of {@link WritableKVState} backed by a {@link MerkleMap}, resulting in a state
 * that is stored in memory.
 * @deprecated This class should be removed together with {@link MerkleStateRoot}. {@code InMemoryWritableStateTest}
 * should be removed as well.
 *
 * @param <K> The type of key for the state
 * @param <V> The type of value for the state
 */
@SuppressWarnings("DuplicatedCode")
@Deprecated
public final class InMemoryWritableKVState<K, V> extends WritableKVStateBase<K, V> {
    /** The underlying merkle tree data structure with the data */
    private final MerkleMap<InMemoryKey<K>, InMemoryValue<K, V>> merkle;

    private final Codec<K> keyCodec;
    private final Codec<V> valueCodec;

    private final long inMemoryValueClassId;

    /**
     * Create a new instance.
     *
     * @param stateKey the state key
     * @param inMemoryValueClassId the class ID for the value
     * @param keyCodec the codec for the key
     * @param valueCodec the codec for the value
     * @param merkleMap The backing merkle map
     */
    public InMemoryWritableKVState(
            @NonNull final String stateKey,
            @NonNull final String serviceName,
            final long inMemoryValueClassId,
            @Nullable Codec<K> keyCodec,
            @NonNull Codec<V> valueCodec,
            @NonNull MerkleMap<InMemoryKey<K>, InMemoryValue<K, V>> merkleMap) {
        super(stateKey, serviceName);
        this.keyCodec = keyCodec;
        this.valueCodec = valueCodec;
        this.inMemoryValueClassId = inMemoryValueClassId;
        this.merkle = Objects.requireNonNull(merkleMap);
    }

    @Override
    protected V readFromDataSource(@NonNull K key) {
        final var k = new InMemoryKey<>(key);
        final var leaf = merkle.get(k);
        final var value = leaf == null ? null : leaf.getValue();
        // Log to transaction state log, what was read
        logMapGet(getStateKey(), key, value);
        return value;
    }

    @NonNull
    @Override
    protected Iterator<K> iterateFromDataSource() {
        final var keySet = merkle.keySet();
        // Log to transaction state log, what was iterated
        logMapIterate(getStateKey(), keySet);
        return keySet.stream().map(InMemoryKey::key).iterator();
    }

    @Override
    protected void putIntoDataSource(@NonNull K key, @NonNull V value) {
        final var k = new InMemoryKey<>(key);
        merkle.put(k, new InMemoryValue<>(inMemoryValueClassId, keyCodec, valueCodec, k, value));
        // Log to transaction state log, what was put
        logMapPut(getStateKey(), key, value);
    }

    @Override
    protected void removeFromDataSource(@NonNull K key) {
        final var k = new InMemoryKey<>(key);
        final var removed = merkle.remove(k);
        // Log to transaction state log, what was removed
        logMapRemove(getStateKey(), key, removed);
    }

    /** {@inheritDoc} */
    @Override
    public long sizeOfDataSource() {
        final var size = merkle.size();
        // Log to transaction state log, size of map
        logMapGetSize(getStateKey(), size);
        return size;
    }
}
