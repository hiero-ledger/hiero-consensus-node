// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.queue;

import static com.swirlds.state.merkle.StateUtils.computeLabel;
import static com.swirlds.state.merkle.logging.StateLogger.logQueuePeek;
import static java.util.Objects.requireNonNull;

import com.swirlds.state.merkle.MerkleStateRoot;
import com.swirlds.state.spi.ReadableQueueState;
import com.swirlds.state.spi.ReadableQueueStateBase;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Iterator;

/**
 * An implementation of {@link ReadableQueueState} that uses a merkle {@link QueueNode} as the backing store.
 * @deprecated This class should be removed together with {@link MerkleStateRoot}.
 * @param <E> The type of elements in the queue.
 */
@Deprecated
public class BackedReadableQueueState<E> extends ReadableQueueStateBase<E> {

    private final QueueNode<E> dataSource;

    /** Create a new instance */
    public BackedReadableQueueState(
            @NonNull final String serviceName, @NonNull final String stateKey, @NonNull final QueueNode<E> node) {
        super(serviceName, stateKey);
        this.dataSource = requireNonNull(node);
    }

    @Nullable
    @Override
    protected E peekOnDataSource() {
        final var value = dataSource.peek();
        logQueuePeek(computeLabel(serviceName, stateKey), value);
        return value;
    }

    @NonNull
    @Override
    protected Iterator<E> iterateOnDataSource() {
        return dataSource.iterator();
    }
}
