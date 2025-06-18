// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.disk;

import static com.swirlds.state.BinaryStateUtils.computeLabel;
import static com.swirlds.state.BinaryStateUtils.getValidatedStateId;
import static com.swirlds.state.merkle.logging.StateLogger.logQueueAdd;
import static com.swirlds.state.merkle.logging.StateLogger.logQueueRemove;
import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.Codec;
import com.swirlds.state.BinaryState;
import com.swirlds.state.spi.WritableQueueStateBase;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;

/**
 * An implementation of {@link com.swirlds.state.spi.WritableQueueState} backed by a {@link VirtualMap}, resulting in a state
 * that is stored on disk.
 *
 * @param <E> The type of element in the queue
 */
public class OnDiskWritableQueueState<E> extends WritableQueueStateBase<E> {

    /** The backing merkle data structure to use */
    @NonNull
    private final BinaryState binaryState;

    @NonNull
    private final Codec<E> valueCodec;

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
    public OnDiskWritableQueueState(
            @NonNull final String serviceName,
            @NonNull final String stateKey,
            @NonNull final Codec<E> valueCodec,
            @NonNull final BinaryState binaryState) {
        super(serviceName, stateKey);
        this.binaryState = requireNonNull(binaryState);
        this.valueCodec = requireNonNull(valueCodec);
        this.stateId = getValidatedStateId(serviceName, stateKey);
        this.label = computeLabel(serviceName, stateKey);
    }

    /** {@inheritDoc} */
    @Override
    protected void addToDataSource(@NonNull E element) {
        binaryState.queueAdd(stateId, valueCodec, element);
        // Log to transaction state log, what was added
        logQueueAdd(label, element);
    }

    /** {@inheritDoc} */
    @Override
    protected void removeFromDataSource() {
        E removedElement = binaryState.queuePoll(stateId, valueCodec);
        // Log to transaction state log, what was removed
        logQueueRemove(computeLabel(serviceName, stateKey), removedElement);
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    protected Iterator<E> iterateOnDataSource() {
        return binaryState.createQueueIterator(stateId, valueCodec);
    }
}
