// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.disk;

import static com.swirlds.state.merkle.StateUtils.getStateKeyForSingleton;
import static com.swirlds.state.merkle.logging.StateLogger.logSingletonRead;
import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.merkle.StateValue;
import com.swirlds.state.merkle.StateValue.StateValueCodec;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableSingletonStateBase;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * An implementation of {@link ReadableSingletonState} backed by a {@link VirtualMap}, resulting in a state
 * that is stored on disk.
 *
 * @param <V> The type of the value
 */
public class OnDiskReadableSingletonState<V> extends ReadableSingletonStateBase<V> {

    /** The backing merkle data structure to use */
    @NonNull
    private final VirtualMap virtualMap;

    @NonNull
    private final Codec<StateValue<V>> stateValueCodec;

    /**
     * Create a new instance
     *
     * @param serviceName  the service name
     * @param stateId      the state ID
     * @param virtualMap   the backing merkle data structure to use
     */
    public OnDiskReadableSingletonState(
            @NonNull final String serviceName,
            final int stateId,
            @NonNull final Codec<V> valueCodec,
            @NonNull final VirtualMap virtualMap) {
        super(serviceName, stateId);
        this.stateValueCodec = new StateValueCodec<>(stateId, requireNonNull(valueCodec));
        this.virtualMap = requireNonNull(virtualMap);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected V readFromDataSource() {
        final Bytes key = getStateKeyForSingleton(stateId);
        final StateValue<V> stateValue = virtualMap.get(key, stateValueCodec);
        final V value = stateValue != null ? stateValue.value() : null;
        // Log to transaction state log, what was read
        logSingletonRead(StateMetadata.computeLabel(serviceName, stateId), value);
        return value;
    }
}
