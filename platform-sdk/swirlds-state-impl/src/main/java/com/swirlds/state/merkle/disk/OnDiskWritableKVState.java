// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.disk;

import static com.swirlds.state.BinaryStateUtils.computeLabel;
import static com.swirlds.state.BinaryStateUtils.getValidatedStateId;
import static com.swirlds.state.merkle.logging.StateLogger.logMapGet;
import static com.swirlds.state.merkle.logging.StateLogger.logMapGetSize;
import static com.swirlds.state.merkle.logging.StateLogger.logMapIterate;
import static com.swirlds.state.merkle.logging.StateLogger.logMapPut;
import static com.swirlds.state.merkle.logging.StateLogger.logMapRemove;
import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.Codec;
import com.swirlds.state.merkle.VirtualMapBinaryState;
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
    private final VirtualMapBinaryState binaryState;

    @NonNull
    private final Codec<K> keyCodec;

    @NonNull
    private final Codec<V> valueCodec;

    private final int stateId;
    private final String label;

    /**
     * Create a new instance
     *
     * @param serviceName  the service name
     * @param stateKey     the state key
     * @param keyCodec     the codec for the key
     * @param valueCodec   the codec for the value
     * @param binaryState  the interface for accessing and modifying state
     */
    public OnDiskWritableKVState(
            @NonNull final String serviceName,
            @NonNull final String stateKey,
            @NonNull final Codec<K> keyCodec,
            @NonNull final Codec<V> valueCodec,
            @NonNull final VirtualMapBinaryState binaryState) {
        super(serviceName, stateKey);
        this.keyCodec = requireNonNull(keyCodec);
        this.valueCodec = requireNonNull(valueCodec);
        this.binaryState = requireNonNull(binaryState);
        this.stateId = getValidatedStateId(serviceName, stateKey);
        this.label = computeLabel(serviceName, stateKey);
    }

    /** {@inheritDoc} */
    @Override
    protected V readFromDataSource(@NonNull K key) {
        final var value = binaryState.getValueByKey(stateId, keyCodec, key, valueCodec);
        // Log to transaction state log, what was read
        logMapGet(label, key, value);
        return value;
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    protected Iterator<K> iterateFromDataSource() {
        // Log to transaction state log, what was iterated
        logMapIterate(label, binaryState, keyCodec);
        return new OnDiskIterator<>(binaryState, getValidatedStateId(serviceName, stateKey));
    }

    /** {@inheritDoc} */
    @Override
    protected void putIntoDataSource(@NonNull K key, @NonNull V value) {
        binaryState.putKeyValuePair(stateId, keyCodec, key, valueCodec, value);
        // Log to transaction state log, what was put
        logMapPut(label, key, value);
    }

    /** {@inheritDoc} */
    @Override
    protected void removeFromDataSource(@NonNull K key) {
        final V removed = binaryState.removeKeyValuePair(stateId, keyCodec, key, valueCodec);
        // Log to transaction state log, what was removed
        logMapRemove(label, key, removed);
    }

    /** {@inheritDoc} */
    @Override
    public long sizeOfDataSource() {
        final long size = binaryState.size();
        // Log to transaction state log, size of map
        logMapGetSize(label, size);
        return size;
    }
}
