// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.disk;

import static com.swirlds.state.merkle.StateUtils.computeLabel;
import static com.swirlds.state.merkle.StateUtils.getStateKeyForKv;
import static com.swirlds.state.merkle.logging.StateLogger.logMapGet;
import static com.swirlds.state.merkle.logging.StateLogger.logMapGetSize;
import static com.swirlds.state.merkle.logging.StateLogger.logMapIterate;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.platform.state.StateValue;
import com.hedera.pbj.runtime.Codec;
import com.swirlds.state.merkle.StateUtils;
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

    /**
     * Create a new instance
     *
     * @param serviceName the service name
     * @param stateKey    the state key
     * @param keyCodec    the codec for the key
     * @param virtualMap  the backing merkle data structure to use
     */
    public OnDiskReadableKVState(
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
        return new OnDiskIterator<>(virtualMap, keyCodec, StateUtils.stateIdFor(serviceName, stateKey));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Deprecated
    public long size() {
        final var size = virtualMap.size();
        // Log to transaction state log, size of map
        logMapGetSize(computeLabel(serviceName, stateKey), size);
        return size;
    }

    @Override
    public void warm(@NonNull final K key) {
        virtualMap.warm(getStateKeyForKv(serviceName, stateKey, key));
    }
}
