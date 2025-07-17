// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.disk;

import static com.swirlds.state.BinaryStateUtils.computeLabel;
import static com.swirlds.state.BinaryStateUtils.getValidatedStateId;
import static com.swirlds.state.merkle.logging.StateLogger.logSingletonRead;
import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.Codec;
import com.swirlds.state.BinaryState;
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
    private final BinaryState binaryState;

    @NonNull
    private final Codec<T> valueCodec;

    private final int stateId;
    private final String label;

    /**
     * Create a new instance
     *
     * @param serviceName  the service name
     * @param stateKey     the state key
     * @param valueCodec   the codec for the value
     * @param binaryState  the interface for accessing and modifying state
     */
    public OnDiskReadableSingletonState(
            @NonNull final String serviceName,
            @NonNull final String stateKey,
            @NonNull final Codec<T> valueCodec,
            @NonNull final BinaryState binaryState) {
        super(serviceName, stateKey);
        this.binaryState = requireNonNull(binaryState);
        this.valueCodec = requireNonNull(valueCodec);
        this.stateId = getValidatedStateId(serviceName, stateKey);
        this.label = computeLabel(serviceName, stateKey);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected T readFromDataSource() {
        final T value = binaryState.getSingleton(stateId, valueCodec);
        // Log to transaction state log, what was read
        logSingletonRead(label, value);
        return value;
    }
}
