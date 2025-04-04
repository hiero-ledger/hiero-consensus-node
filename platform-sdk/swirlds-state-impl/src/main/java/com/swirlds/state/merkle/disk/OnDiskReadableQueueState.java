// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.disk;

import static com.swirlds.state.merkle.StateUtils.computeLabel;
import static com.swirlds.state.merkle.StateUtils.getVirtualMapKey;
import static com.swirlds.state.merkle.logging.StateLogger.logQueuePeek;

import com.hedera.pbj.runtime.Codec;
import com.swirlds.base.utility.Pair;
import com.swirlds.state.merkle.queue.QueueNode;
import com.swirlds.state.merkle.queue.QueueState;
import com.swirlds.state.merkle.queue.QueueStateCodec;
import com.swirlds.state.spi.ReadableQueueState;
import com.swirlds.state.spi.ReadableQueueStateBase;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * An implementation of {@link ReadableQueueState} that uses a merkle {@link QueueNode} as the backing store.
 * @param <E> The type of elements in the queue.
 */
public class OnDiskReadableQueueState<E> extends ReadableQueueStateBase<E> {

    @NonNull
    private final OnDiskQueueHelper<E> queueHelper;

    private final VirtualMap virtualMap;

    private final Codec<E> valueCodec;

    /** Create a new instance */
    public OnDiskReadableQueueState(
            @NonNull final String serviceName,
            @NonNull final String stateKey,
            @NonNull final Codec<E> valueCodec,
            @NonNull final VirtualMap virtualMap) {
        super(serviceName, stateKey);

        this.valueCodec = valueCodec;
        this.virtualMap = virtualMap;

        this.queueHelper = new OnDiskQueueHelper<>(serviceName, stateKey, virtualMap, valueCodec);
    }

    @Nullable
    @Override
    protected E peekOnDataSource() {
        final QueueState state = queueHelper.getState();
        final E value = state.isEmpty() ? null : queueHelper.getFromStore(state.getHead());
        // Log to transaction state log, what was peeked
        logQueuePeek(computeLabel(serviceName, stateKey), value);
        return value;
    }

    /** Iterate over all elements */
    @NonNull
    @Override
    protected Iterator<E> iterateOnDataSource() {
        return queueHelper.iterateOnDataSource();
    }

    public Pair<QueueState, List<E>> getQueueContents() {
        QueueState state = virtualMap.get(getVirtualMapKey(serviceName, stateKey), QueueStateCodec.INSTANCE);
        if (state == null) {
            return new Pair<>(null, new ArrayList<>());
        }
        List<E> contents = new ArrayList<>();
        for (long i = state.getHead(); i < state.getTail(); i++) {
            E element = virtualMap.get(getVirtualMapKey(serviceName, stateKey, i), valueCodec);
            contents.add(element);
        }
        return new Pair<>(state, contents);
    }
}
