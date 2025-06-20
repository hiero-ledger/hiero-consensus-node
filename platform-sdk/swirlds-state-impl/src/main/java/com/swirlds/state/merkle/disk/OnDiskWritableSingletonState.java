// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.disk;

import static com.swirlds.state.merkle.StateUtils.computeLabel;
import static com.swirlds.state.merkle.logging.StateLogger.logSingletonRead;
import static com.swirlds.state.merkle.logging.StateLogger.logSingletonRemove;
import static com.swirlds.state.merkle.logging.StateLogger.logSingletonWrite;
import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.Codec;
import com.swirlds.state.merkle.StateUtils;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableSingletonStateBase;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * An implementation of {@link WritableSingletonState} backed by a {@link VirtualMap}, resulting in a state
 * that is stored on disk.
 *
 * @param <T> The type of the value
 */
public class OnDiskWritableSingletonState<T> extends WritableSingletonStateBase<T> {

    /** The backing merkle data structure to use */
    @NonNull
    private final VirtualMap virtualMap;

    @NonNull
    private final Codec<T> valueCodec;

    /**
     * Create a new instance
     *
     * @param serviceName  the service name
     * @param stateKey     the state key
     * @param valueCodec   the codec for the value
     * @param virtualMap   the backing merkle data structure to use
     */
    public OnDiskWritableSingletonState(
            @NonNull final String serviceName,
            @NonNull final String stateKey,
            @NonNull final Codec<T> valueCodec,
            @NonNull final VirtualMap virtualMap) {
        super(serviceName, stateKey);
        this.valueCodec = requireNonNull(valueCodec);
        this.virtualMap = requireNonNull(virtualMap);
    }

    /** {@inheritDoc} */
    @Override
    protected T readFromDataSource() {
        final var value = OnDiskSingletonHelper.getFromStore(serviceName, stateKey, virtualMap, valueCodec);
        // Log to transaction state log, what was read
        logSingletonRead(computeLabel(serviceName, stateKey), value);
        return value;
    }

    /** {@inheritDoc} */
    @Override
    protected void putIntoDataSource(@NonNull T value) {
        virtualMap.put(StateUtils.getVirtualMapKeyForSingleton(serviceName, stateKey), value, valueCodec);
        // Log to transaction state log, what was put
        logSingletonWrite(computeLabel(serviceName, stateKey), value);
    }

    /** {@inheritDoc} */
    @Override
    protected void removeFromDataSource() {
        final var removed =
                virtualMap.remove(StateUtils.getVirtualMapKeyForSingleton(serviceName, stateKey), valueCodec);
        // Log to transaction state log, what was removed
        logSingletonRemove(computeLabel(serviceName, stateKey), removed);
    }
}
