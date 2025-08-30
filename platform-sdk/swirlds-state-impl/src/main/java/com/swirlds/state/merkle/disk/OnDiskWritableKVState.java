// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.disk;

import static com.swirlds.state.merkle.StateUtils.computeLabel;
import static com.swirlds.state.merkle.StateUtils.getStateKeyForKv;
import static com.swirlds.state.merkle.StateUtils.getStateValue;
import static com.swirlds.state.merkle.logging.StateLogger.logMapGet;
import static com.swirlds.state.merkle.logging.StateLogger.logMapGetSize;
import static com.swirlds.state.merkle.logging.StateLogger.logMapIterate;
import static com.swirlds.state.merkle.logging.StateLogger.logMapPut;
import static com.swirlds.state.merkle.logging.StateLogger.logMapRemove;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.platform.state.StateValue;
import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.merkle.StateUtils;
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

    /**
     * Create a new instance
     *
     * @param serviceName the service name
     * @param stateKey    the state key
     * @param keyCodec    the codec for the key
     * @param virtualMap  the backing merkle data structure to use
     */
    public OnDiskWritableKVState(
            @NonNull final String serviceName,
            @NonNull final String stateKey,
            @NonNull final Codec<K> keyCodec,
            @NonNull final VirtualMap virtualMap) {
        super(serviceName, stateKey);
        this.keyCodec = requireNonNull(keyCodec);
        this.virtualMap = requireNonNull(virtualMap);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected V readFromDataSource(@NonNull K key) {
        final StateValue stateValue = virtualMap.get(getStateKeyForKv(serviceName, stateKey, key), StateValue.PROTOBUF);
        final V value = stateValue != null ? stateValue.value().as() : null;
        // Log to transaction state log, what was read
        logMapGet(computeLabel(serviceName, stateKey), key, value);
        return value;
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    protected Iterator<K> iterateFromDataSource() {
        // Log to transaction state log, what was iterated
        logMapIterate(computeLabel(serviceName, stateKey), virtualMap, keyCodec);
        return new OnDiskIterator<>(virtualMap, StateUtils.stateIdFor(serviceName, stateKey));
    }

    /** {@inheritDoc} */
    @Override
    protected void putIntoDataSource(@NonNull K key, @NonNull V value) {
        assert keyCodec.toBytes(key) != null;

        final Bytes keyBytes = getStateKeyForKv(serviceName, stateKey, key);
        final StateValue stateValue = getStateValue(serviceName, stateKey, value);

        virtualMap.put(keyBytes, stateValue, StateValue.PROTOBUF);
        // Log to transaction state log, what was put
        logMapPut(computeLabel(serviceName, stateKey), key, value);
    }

    /** {@inheritDoc} */
    @Override
    protected void removeFromDataSource(@NonNull K key) {
        final StateValue stateValue =
                virtualMap.remove(getStateKeyForKv(serviceName, stateKey, key), StateValue.PROTOBUF);
        final var removedValue = stateValue != null ? stateValue.value().as() : null;
        // Log to transaction state log, what was removed
        logMapRemove(computeLabel(serviceName, stateKey), key, removedValue);
    }

    /** {@inheritDoc} */
    @Override
    public long sizeOfDataSource() {
        final var size = virtualMap.size();
        // Log to transaction state log, size of map
        logMapGetSize(computeLabel(serviceName, stateKey), size);
        return size;
    }
}
