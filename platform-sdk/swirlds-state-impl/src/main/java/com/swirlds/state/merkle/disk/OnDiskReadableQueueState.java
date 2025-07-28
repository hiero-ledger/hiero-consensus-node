// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.disk;

import static com.swirlds.state.merkle.StateUtils.computeLabel;
import static com.swirlds.state.merkle.logging.StateLogger.logQueuePeek;

import com.hedera.pbj.runtime.Codec;
import com.swirlds.state.merkle.queue.QueueState;
import com.swirlds.state.spi.ReadableQueueState;
import com.swirlds.state.spi.ReadableQueueStateBase;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Iterator;
import java.util.Objects;

/**
 * An implementation of {@link ReadableQueueState} backed by a {@link VirtualMap}, resulting in a state
 * that is stored on disk.
 *
 * @param <E> The type of elements in the queue.
 */
public class OnDiskReadableQueueState<E> extends ReadableQueueStateBase<E> {

    @NonNull
    private final OnDiskQueueHelper<E> onDiskQueueHelper;

    /**
     * Create a new instance
     *
     * @param serviceName  the service name
     * @param stateKey     the state key
     * @param valueCodec   the codec for the value
     * @param virtualMap   the backing merkle data structure to use
     */
    public OnDiskReadableQueueState(
            @NonNull final String serviceName,
            @NonNull final String stateKey,
            @NonNull final Codec<E> valueCodec,
            @NonNull final VirtualMap virtualMap) {
        super(serviceName, stateKey);
        this.onDiskQueueHelper = new OnDiskQueueHelper<>(serviceName, stateKey, virtualMap, valueCodec);
    }

    @Nullable
    @Override
    protected E peekOnDataSource() {
        final QueueState state = onDiskQueueHelper.getState();
        Objects.requireNonNull(state);
        final E value = state.isEmpty() ? null : onDiskQueueHelper.getFromStore(state.getHead());
        // Log to transaction state log, what was peeked
        logQueuePeek(computeLabel(serviceName, stateKey), value);
        return value;
    }

    /** Iterate over all elements */
    @NonNull
    @Override
    protected Iterator<E> iterateOnDataSource() {
        return onDiskQueueHelper.iterateOnDataSource();
    }
}
