// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.disk;

import static com.swirlds.state.merkle.logging.StateLogger.logQueueAdd;
import static com.swirlds.state.merkle.logging.StateLogger.logQueueIterate;
import static com.swirlds.state.merkle.logging.StateLogger.logQueueRemove;
import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.Codec;
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
public class OnDiskWritableQueueState<V> extends WritableQueueStateBase<V> {

    @NonNull
    private final OnDiskQueueHelper<V> onDiskQueueHelper;

    /**
     * Create a new instance
     *
     * @param stateId     the state ID
     * @param label       the service label
     * @param virtualMap  the backing merkle data structure to use
     */
    public OnDiskWritableQueueState(
            final int stateId,
            @NonNull final String label,
            @NonNull final Codec<V> valueCodec,
            @NonNull final VirtualMap virtualMap) {
        super(stateId, requireNonNull(label));
        this.onDiskQueueHelper = new OnDiskQueueHelper<>(stateId, valueCodec, virtualMap);
    }

    /** {@inheritDoc} */
    @Override
    protected void addToDataSource(@NonNull V value) {
        QueueState state = onDiskQueueHelper.getState();
        if (state == null) {
            // Adding to this Queue State first time - initialize QueueState.
            state = new QueueState(1, 1);
        }
        onDiskQueueHelper.addToStore(state.tail(), value);
        // increment tail and update state
        onDiskQueueHelper.updateState(state.elementAdded());
        // Log to transaction state log, what was added
        logQueueAdd(label, value);
    }

    /** {@inheritDoc} */
    @Override
    protected void removeFromDataSource() {
        final QueueState state = requireNonNull(onDiskQueueHelper.getState());
        if (!OnDiskQueueHelper.isEmpty(state)) {
            final V removedValue = onDiskQueueHelper.removeFromStore(state.head());
            // increment head and update state
            onDiskQueueHelper.updateState(state.elementRemoved());
            // Log to transaction state log, what was removed
            logQueueRemove(label, removedValue);
        } else {
            // Should it be considered an error?
            // Log to transaction state log, what was removed
            logQueueRemove(label, null);
        }
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    protected Iterator<V> iterateOnDataSource() {
        final QueueState state = onDiskQueueHelper.getState();
        if (state == null) {
            // Empty iterator
            return onDiskQueueHelper.iterateOnDataSource(0, 0);
        } else {
            final Iterator<V> it = onDiskQueueHelper.iterateOnDataSource(state.head(), state.tail());
            // Log to transaction state log, what was iterated
            logQueueIterate(label, state.tail() - state.head(), it);
            return it;
        }
    }
}
