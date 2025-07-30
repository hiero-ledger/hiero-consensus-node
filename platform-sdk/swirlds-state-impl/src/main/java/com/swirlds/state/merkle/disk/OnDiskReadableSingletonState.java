// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.disk;

import static com.swirlds.state.merkle.StateUtils.computeLabel;
import static com.swirlds.state.merkle.logging.StateLogger.logSingletonRead;
import static java.util.Objects.requireNonNull;

import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableSingletonStateBase;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * An implementation of {@link ReadableSingletonState} backed by a {@link VirtualMap}, resulting in a state
 * that is stored on disk.
 *
 * @param <T> The type of the value
 */
public class OnDiskReadableSingletonState<T> extends ReadableSingletonStateBase<T> {

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
    public OnDiskReadableSingletonState(
            @NonNull final String serviceName, @NonNull final String stateKey, @NonNull final VirtualMap virtualMap) {
        super(serviceName, stateKey);
        this.virtualMap = requireNonNull(virtualMap);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected T readFromDataSource() {
        final T value = OnDiskSingletonHelper.getFromStore(serviceName, stateKey, virtualMap);
        // Log to transaction state log, what was read
        logSingletonRead(computeLabel(serviceName, stateKey), value);
        return value;
    }
}
