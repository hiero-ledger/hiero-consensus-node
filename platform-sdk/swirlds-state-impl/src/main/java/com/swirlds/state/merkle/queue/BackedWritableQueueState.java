// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.queue;

import static com.swirlds.state.merkle.StateUtils.computeLabel;
import static com.swirlds.state.merkle.logging.StateLogger.logQueueAdd;
import static com.swirlds.state.merkle.logging.StateLogger.logQueueRemove;
import static java.util.Objects.requireNonNull;

import com.swirlds.state.merkle.MerkleStateRoot;
import com.swirlds.state.spi.WritableQueueStateBase;
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

    public BackedWritableQueueState(
            @NonNull final String serviceName, @NonNull final String stateKey, @NonNull final QueueNode<E> node) {
        super(serviceName, stateKey);
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
        // Log to transaction state log, what was added
        logQueueAdd(computeLabel(serviceName, stateKey), element);
    }

    @Override
    protected void removeFromDataSource() {
        final var removedValue = dataSource.remove();
        // Log to transaction state log, what was added
        logQueueRemove(computeLabel(serviceName, stateKey), removedValue);
    }
}
