// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.queue;

import static java.util.Objects.requireNonNull;

import com.swirlds.state.spi.ReadableQueueState;
import com.swirlds.state.spi.ReadableQueueStateBase;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Iterator;

/**
 * An implementation of {@link ReadableQueueState} that uses a merkle {@link QueueNode} as the backing store.
 * @param <E> The type of elements in the queue.
 */
public class ReadableQueueStateImpl<E> extends ReadableQueueStateBase<E> {
    private final QueueNode<E> dataSource;

    /** Create a new instance */
    public ReadableQueueStateImpl(@NonNull final String stateKey, @NonNull final QueueNode<E> node) {
        super(stateKey);
        this.dataSource = requireNonNull(node);
    }

    @Nullable
    @Override
    protected E peekOnDataSource() {
        return dataSource.peek();
    }

    @NonNull
    @Override
    protected Iterator<E> iterateOnDataSource() {
        return dataSource.iterator();
    }
}
