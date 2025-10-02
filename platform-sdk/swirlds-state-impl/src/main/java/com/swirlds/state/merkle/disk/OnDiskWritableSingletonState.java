// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.disk;

import static com.swirlds.state.merkle.StateUtils.getStateKeyForSingleton;
import static com.swirlds.state.merkle.StateUtils.getStateValueForSingleton;
import static com.swirlds.state.merkle.logging.StateLogger.logSingletonRead;
import static com.swirlds.state.merkle.logging.StateLogger.logSingletonRemove;
import static com.swirlds.state.merkle.logging.StateLogger.logSingletonWrite;
import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.merkle.StateValue;
import com.swirlds.state.merkle.StateValue.StateValueCodec;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableSingletonStateBase;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * An implementation of {@link WritableSingletonState} backed by a {@link VirtualMap}, resulting in a state
 * that is stored on disk.
 *
 * @param <V> The type of the value
 */
public class OnDiskWritableSingletonState<V> extends WritableSingletonStateBase<V> {

    /** The backing merkle data structure to use */
    @NonNull
    private final VirtualMap virtualMap;

    @NonNull
    private final Codec<StateValue<V>> stateValueCodec;

    /**
     * Create a new instance
     *
     * @param stateId      the state ID
     * @param label        the service label
     * @param valueCodec   the protobuf value codec
     * @param virtualMap   the backing merkle data structure to use
     */
    public OnDiskWritableSingletonState(
            final int stateId,
            @NonNull final String label,
            @NonNull final Codec<V> valueCodec,
            @NonNull final VirtualMap virtualMap) {
        super(stateId, requireNonNull(label));
        this.stateValueCodec = new StateValueCodec<>(stateId, requireNonNull(valueCodec));
        this.virtualMap = requireNonNull(virtualMap);
    }

    /** {@inheritDoc} */
    @Override
    protected V readFromDataSource() {
        final Bytes stateKey = getStateKeyForSingleton(stateId);
        final StateValue<V> stateValue = virtualMap.get(stateKey, stateValueCodec);
        final V value = stateValue != null ? stateValue.value() : null;
        // Log to transaction state log, what was read
        logSingletonRead(label, value);
        return value;
    }

    /** {@inheritDoc} */
    @Override
    protected void putIntoDataSource(@NonNull V value) {
        final Bytes stateKey = getStateKeyForSingleton(stateId);
        final StateValue<V> stateValue = getStateValueForSingleton(stateId, value);

        virtualMap.put(stateKey, stateValue, stateValueCodec);
        // Log to transaction state log, what was put
        logSingletonWrite(label, value);
    }

    /** {@inheritDoc} */
    @Override
    protected void removeFromDataSource() {
        final Bytes stateKey = getStateKeyForSingleton(stateId);
        final StateValue<V> stateValue = virtualMap.remove(stateKey, stateValueCodec);
        final var removedValue = stateValue != null ? stateValue.value() : null;
        // Log to transaction state log, what was removed
        logSingletonRemove(label, removedValue);
    }
}
