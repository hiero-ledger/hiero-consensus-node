// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.disk;

import static com.swirlds.state.merkle.logging.StateLogger.logQueueIterate;
import static com.swirlds.state.merkle.logging.StateLogger.logQueuePeek;

import com.hedera.pbj.runtime.Codec;
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
 * @param <V> The type of elements in the queue.
 */
public class OnDiskReadableQueueState<V> extends ReadableQueueStateBase<V> {

    @NonNull
    private final OnDiskQueueHelper<V> onDiskQueueHelper;

    /**
     * Create a new instance
     *
     * @param stateId     the state ID
     * @param label       the service label
     * @param virtualMap  the backing merkle data structure to use
     */
    public OnDiskReadableQueueState(
            final int stateId,
            @NonNull final String label,
            @NonNull final Codec<V> valueCodec,
            @NonNull final VirtualMap virtualMap) {
        super(stateId, label);
        this.onDiskQueueHelper = new OnDiskQueueHelper<>(stateId, valueCodec, virtualMap);
    }

    @Nullable
    @Override
    protected V peekOnDataSource() {
        final QueueState state = onDiskQueueHelper.getState();
        Objects.requireNonNull(state);
        final V value = OnDiskQueueHelper.isEmpty(state) ? null : onDiskQueueHelper.getFromStore(state.head());
        // Log to transaction state log, what was peeked
        logQueuePeek(label, value);
        return value;
    }

    /** Iterate over all elements */
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
