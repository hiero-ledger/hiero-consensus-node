// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.memory;

import static com.swirlds.state.merkle.logging.StateLogger.logMapGet;
import static com.swirlds.state.merkle.logging.StateLogger.logMapGetSize;
import static com.swirlds.state.merkle.logging.StateLogger.logMapIterate;

import com.swirlds.merkle.map.MerkleMap;
import com.swirlds.state.merkle.MerkleStateRoot;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableKVStateBase;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;
import java.util.Objects;

/**
 * An implementation of {@link ReadableKVState} backed by a {@link MerkleMap}, resulting in a state
 * that is stored in memory.
 *
 * @deprecated This class should be removed together with {@link MerkleStateRoot}. {@code InMemoryReadableStateTest}
 * should be removed as well.
 * @param <K> The type of key for the state
 * @param <V> The type of value for the state
 */
@SuppressWarnings("DuplicatedCode")
@Deprecated
public final class InMemoryReadableKVState<K, V> extends ReadableKVStateBase<K, V> {

    /** The underlying merkle tree data structure with the data */
    private final MerkleMap<InMemoryKey<K>, InMemoryValue<K, V>> merkle;

    /**
     * Create a new instance.
     *
     * @param stateKey the state key
     * @param merkleMap The backing merkle map
     */
    public InMemoryReadableKVState(
            @NonNull final String stateKey,
            @NonNull final String serviceName,
            @NonNull MerkleMap<InMemoryKey<K>, InMemoryValue<K, V>> merkleMap) {
        super(stateKey, serviceName);
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

    /** {@inheritDoc} */
    @Override
    @Deprecated
    public long size() {
        final var size = merkle.size();
        // Log to transaction state log, size of map
        logMapGetSize(getStateKey(), size);
        return size;
    }
}
