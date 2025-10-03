// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.test.fixtures.merkle.queue;

import static java.util.Objects.requireNonNull;

import com.swirlds.state.spi.WritableQueueStateBase;
import com.swirlds.state.test.fixtures.merkle.MerkleStateRoot;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;

/**
 * An implementation of {@link com.swirlds.state.spi.WritableQueueState} based on {@link QueueNode}.
 * @deprecated This class should be removed together with {@link MerkleStateRoot}.
 * @param <E> The type of element in the queue
 */
@Deprecated
public class BackedWritableQueueState<E> extends WritableQueueStateBase<E> {

    private final QueueNode<E> dataSource;

    public BackedWritableQueueState(final int stateId, @NonNull final String label, @NonNull final QueueNode<E> node) {
        super(stateId, requireNonNull(label));
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
        final var removedValue = dataSource.remove();
    }
}
