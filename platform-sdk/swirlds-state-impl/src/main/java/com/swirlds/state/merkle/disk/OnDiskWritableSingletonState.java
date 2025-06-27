// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.disk;

import static com.swirlds.state.BinaryStateUtils.computeLabel;
import static com.swirlds.state.BinaryStateUtils.getValidatedStateId;
import static com.swirlds.state.merkle.logging.StateLogger.logSingletonRead;
import static com.swirlds.state.merkle.logging.StateLogger.logSingletonRemove;
import static com.swirlds.state.merkle.logging.StateLogger.logSingletonWrite;
import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.Codec;
import com.swirlds.state.BinaryState;
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
     * @param binaryState   the backing merkle data structure to use
     */
    public OnDiskWritableSingletonState(
            @NonNull final String serviceName,
            @NonNull final String stateKey,
            @NonNull final Codec<T> valueCodec,
            @NonNull final BinaryState binaryState) {
        super(serviceName, stateKey);
        this.valueCodec = requireNonNull(valueCodec);
        this.binaryState = requireNonNull(binaryState);
        this.stateId = getValidatedStateId(serviceName, stateKey);
        this.label = computeLabel(serviceName, stateKey);
    }

    /** {@inheritDoc} */
    @Override
    protected T readFromDataSource() {
        final T value = binaryState.getSingleton(stateId, valueCodec);
        // Log to transaction state log, what was read
        logSingletonRead(label, value);
        return value;
    }

    /** {@inheritDoc} */
    @Override
    protected void putIntoDataSource(@NonNull T value) {
        binaryState.putSingleton(stateId, valueCodec, value);
        // Log to transaction state log, what was put
        logSingletonWrite(label, value);
    }

    /** {@inheritDoc} */
    @Override
    protected void removeFromDataSource() {
        final var removed = binaryState.removeSingleton(stateId, valueCodec);
        // Log to transaction state log, what was removed
        logSingletonRemove(computeLabel(serviceName, stateKey), removed);
    }
}
