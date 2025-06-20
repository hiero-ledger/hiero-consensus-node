// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.queue;

import static java.util.Objects.requireNonNull;

import com.swirlds.state.spi.WritableQueueStateBase;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;

/**
 * An implementation of {@link com.swirlds.state.spi.WritableQueueState} based on {@link QueueNode}.
 * @param <E> The type of element in the queue
 */
public class WritableQueueStateImpl<E> extends WritableQueueStateBase<E> {
    private final QueueNode<E> dataSource;

    public WritableQueueStateImpl(@NonNull final String stateKey, @NonNull final QueueNode<E> node) {
        super(stateKey);
        this.dataSource = requireNonNull(node);
    }

    @NonNull
    @Override
    protected Iterator<E> iterateOnDataSource() {
        return dataSource.iterator();
    }

    @Override
    protected void addToDataSource(@NonNull E element) {
        dataSource.add(element);
    }

    @Override
    protected void removeFromDataSource() {
        dataSource.remove();
    }
}
