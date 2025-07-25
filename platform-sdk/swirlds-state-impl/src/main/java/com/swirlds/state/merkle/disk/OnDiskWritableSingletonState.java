// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.disk;

import static com.swirlds.state.merkle.StateUtils.computeLabel;
import static com.swirlds.state.merkle.StateUtils.getVirtualMapKeyForSingleton;
import static com.swirlds.state.merkle.StateUtils.getVirtualMapValue;
import static com.swirlds.state.merkle.logging.StateLogger.logSingletonRead;
import static com.swirlds.state.merkle.logging.StateLogger.logSingletonRemove;
import static com.swirlds.state.merkle.logging.StateLogger.logSingletonWrite;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.platform.state.VirtualMapValue;
import com.hedera.pbj.runtime.io.buffer.Bytes;
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

    /**
     * Create a new instance
     *
     * @param serviceName  the service name
     * @param stateKey     the state key
     * @param virtualMap   the backing merkle data structure to use
     */
    public OnDiskWritableSingletonState(
            @NonNull final String serviceName, @NonNull final String stateKey, @NonNull final VirtualMap virtualMap) {
        super(serviceName, stateKey);
        this.virtualMap = requireNonNull(virtualMap);
    }

    /** {@inheritDoc} */
    @Override
    protected T readFromDataSource() {
        final T value = OnDiskSingletonHelper.getFromStore(serviceName, stateKey, virtualMap);
        // Log to transaction state log, what was read
        logSingletonRead(computeLabel(serviceName, stateKey), value);
        return value;
    }

    /** {@inheritDoc} */
    @Override
    protected void putIntoDataSource(@NonNull T value) {
        final Bytes keyBytes = getVirtualMapKeyForSingleton(serviceName, stateKey);
        final VirtualMapValue virtualMapValue = getVirtualMapValue(serviceName, stateKey, value);

        virtualMap.put(keyBytes, virtualMapValue, VirtualMapValue.PROTOBUF);
        // Log to transaction state log, what was put
        logSingletonWrite(computeLabel(serviceName, stateKey), value);
    }

    /** {@inheritDoc} */
    @Override
    protected void removeFromDataSource() {
        final VirtualMapValue virtualMapValue =
                virtualMap.remove(getVirtualMapKeyForSingleton(serviceName, stateKey), VirtualMapValue.PROTOBUF);
        final var removedValue =
                virtualMapValue != null ? virtualMapValue.value().as() : null;
        // Log to transaction state log, what was removed
        logSingletonRemove(computeLabel(serviceName, stateKey), removedValue);
    }
}
