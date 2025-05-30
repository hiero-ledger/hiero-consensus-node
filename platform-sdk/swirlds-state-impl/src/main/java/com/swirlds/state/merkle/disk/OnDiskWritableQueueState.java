// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.disk;

import static com.swirlds.state.merkle.StateUtils.computeLabel;
import static com.swirlds.state.merkle.logging.StateLogger.logQueueAdd;
import static com.swirlds.state.merkle.logging.StateLogger.logQueueRemove;
import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.Codec;
import com.swirlds.state.merkle.StateUtils;
import com.swirlds.state.merkle.queue.QueueState;
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
    private final VirtualMap virtualMap;

    @NonNull
    private final Codec<E> valueCodec;

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
    public OnDiskWritableQueueState(
            @NonNull final String serviceName,
            @NonNull final String stateKey,
            @NonNull final Codec<E> valueCodec,
            @NonNull final VirtualMap virtualMap) {
        super(serviceName, stateKey);
        this.virtualMap = requireNonNull(virtualMap);
        this.valueCodec = requireNonNull(valueCodec);
        this.onDiskQueueHelper = new OnDiskQueueHelper<>(serviceName, stateKey, virtualMap, valueCodec);
    }

    /** {@inheritDoc} */
    @Override
    protected void addToDataSource(@NonNull E element) {
        QueueState state = onDiskQueueHelper.getState();
        if (state == null) {
            // Adding to this Queue State first time - initialize QueueState.
            state = new QueueState();
        }
        virtualMap.put(
                StateUtils.getVirtualMapKeyForQueue(serviceName, stateKey, state.getTailAndIncrement()),
                element,
                valueCodec);
        onDiskQueueHelper.updateState(state);
        // Log to transaction state log, what was added
        logQueueAdd(computeLabel(serviceName, stateKey), element);
    }

    /** {@inheritDoc} */
    @Override
    protected void removeFromDataSource() {
        final QueueState state = requireNonNull(onDiskQueueHelper.getState());
        if (!state.isEmpty()) {
            final var removedValue = virtualMap.remove(
                    StateUtils.getVirtualMapKeyForQueue(serviceName, stateKey, state.getHeadAndIncrement()),
                    valueCodec);
            onDiskQueueHelper.updateState(state);
            // Log to transaction state log, what was removed
            logQueueRemove(computeLabel(serviceName, stateKey), removedValue);
        } else {
            // Log to transaction state log, what was removed
            logQueueRemove(computeLabel(serviceName, stateKey), null);
        }
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    protected Iterator<E> iterateOnDataSource() {
        return onDiskQueueHelper.iterateOnDataSource();
    }
}
