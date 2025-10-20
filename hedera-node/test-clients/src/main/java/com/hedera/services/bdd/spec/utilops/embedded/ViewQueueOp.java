// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.embedded;

import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import com.swirlds.state.spi.ReadableQueueState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;

/**
 * An operation that allows the test author to view a queue value in an embedded state.
 * @param <T> the type of the queue's entries
 */
public class ViewQueueOp<T> extends UtilOp {

    private final String serviceName;
    private final int stateId;
    private final Consumer<ReadableQueueState<T>> observer;

    /**
     * Constructs the operation.
     * @param serviceName the name of the service that manages the record
     * @param stateId the ID of the record in the state
     * @param observer the observer that will receive the record
     */
    public ViewQueueOp(
            @NonNull final String serviceName,
            final int stateId,
            @NonNull final Consumer<ReadableQueueState<T>> observer) {
        this.serviceName = requireNonNull(serviceName);
        this.stateId = stateId;
        this.observer = requireNonNull(observer);
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        final var state = spec.embeddedStateOrThrow();
        final var readableStates = state.getReadableStates(serviceName);
        final var queue = requireNonNull(readableStates.<T>getQueue(stateId));
        observer.accept(queue);
        return false;
    }
}
