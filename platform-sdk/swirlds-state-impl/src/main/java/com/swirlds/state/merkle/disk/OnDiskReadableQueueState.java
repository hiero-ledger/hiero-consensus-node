// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.disk;

import static com.swirlds.state.BinaryStateUtils.computeLabel;
import static com.swirlds.state.BinaryStateUtils.getValidatedStateId;
import static com.swirlds.state.merkle.logging.StateLogger.logQueuePeek;

import com.hedera.pbj.runtime.Codec;
import com.swirlds.state.BinaryState;
import com.swirlds.state.spi.ReadableQueueState;
import com.swirlds.state.spi.ReadableQueueStateBase;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Iterator;

/**
 * An implementation of {@link ReadableQueueState} backed by a {@link VirtualMap}, resulting in a state
 * that is stored on disk.
 *
 * @param <E> The type of elements in the queue.
 */
public class OnDiskReadableQueueState<E> extends ReadableQueueStateBase<E> {

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
    public OnDiskReadableQueueState(
            @NonNull final String serviceName,
            @NonNull final String stateKey,
            @NonNull final Codec<E> valueCodec,
            @NonNull final BinaryState binaryState) {
        super(serviceName, stateKey);
        this.binaryState = binaryState;
        this.valueCodec = valueCodec;
        this.stateId = getValidatedStateId(serviceName, stateKey);
        this.label = computeLabel(serviceName, stateKey);
    }

    @Nullable
    @Override
    protected E peekOnDataSource() {
        final E value = binaryState.queuePeek(stateId, valueCodec);
        // Log to transaction state log, what was peeked
        logQueuePeek(label, value);
        return value;
    }

    /** Iterate over all elements */
    @NonNull
    @Override
    protected Iterator<E> iterateOnDataSource() {
        return binaryState.createQueueIterator(stateId, valueCodec);
    }
}
