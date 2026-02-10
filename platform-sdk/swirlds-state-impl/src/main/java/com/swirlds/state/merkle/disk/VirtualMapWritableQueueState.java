// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.disk;

import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.Codec;
import com.swirlds.state.binary.QueueState;
import com.swirlds.state.spi.WritableQueueStateBase;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;

/**
 * An implementation of {@link com.swirlds.state.spi.WritableQueueState} backed by a {@link VirtualMap}, resulting in a state
 * that is stored on disk.
 *
 * @param <V> The type of element in the queue
 */
public class VirtualMapWritableQueueState<V> extends WritableQueueStateBase<V> {

    @NonNull
    private final VirtualMapQueueHelper<V> virtualMapQueueHelper;

    /**
     * Create a new instance
     *
     * @param stateId     the state ID
     * @param label       the service label
     * @param virtualMap  the backing merkle data structure to use
     */
    public VirtualMapWritableQueueState(
            final int stateId,
            @NonNull final String label,
            @NonNull final Codec<V> valueCodec,
            @NonNull final VirtualMap virtualMap) {
        super(stateId, requireNonNull(label));
        this.virtualMapQueueHelper = new VirtualMapQueueHelper<>(stateId, valueCodec, virtualMap);
    }

    /** {@inheritDoc} */
    @Override
    protected void addToDataSource(@NonNull V value) {
        QueueState state = virtualMapQueueHelper.getState();
        if (state == null) {
            // Adding to this Queue State first time - initialize QueueState.
            state = new QueueState(1, 1);
        }
        virtualMapQueueHelper.addToStore(state.tail(), value);
        // increment tail and update state
        virtualMapQueueHelper.updateState(state.elementAdded());
    }

    /** {@inheritDoc} */
    @Override
    protected void removeFromDataSource() {
        final QueueState state = requireNonNull(virtualMapQueueHelper.getState());
        if (!VirtualMapQueueHelper.isEmpty(state)) {
            virtualMapQueueHelper.removeFromStore(state.head());
            // increment head and update state
            virtualMapQueueHelper.updateState(state.elementRemoved());
        }
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    protected Iterator<V> iterateOnDataSource() {
        final QueueState state = virtualMapQueueHelper.getState();
        if (state == null) {
            // Empty iterator
            return virtualMapQueueHelper.iterateOnDataSource(0, 0);
        } else {
            return virtualMapQueueHelper.iterateOnDataSource(state.head(), state.tail());
        }
    }
}
