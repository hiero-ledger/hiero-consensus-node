// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.systemtasks;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.state.systemtask.SystemTask;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * Provides context for handling a {@link SystemTask}, including access to configuration, network info, time,
 * and stores; and a way to enqueue additional system tasks.
 */
public interface SystemTaskContext {
    /**
     * The system task being handled.
     */
    @NonNull
    SystemTask currentTask();

    /**
     * Enqueue a new system task to be processed when capacity is available.
     * <p>
     * As a state change, is committed atomically any with other state changes by the {@link SystemTaskHandler}
     * receiving this context; i.e., if {@link SystemTaskHandler#handle(SystemTaskContext)} throws an exception
     * after calling this method, the enqueued task will not be added to the queue.
     * @param task the task to enqueue
     */
    void offer(@NonNull SystemTask task);

    /**
     * A {@link StoreFactory} for accessing readable and writable stores and service APIs.
     */
    @NonNull
    StoreFactory storeFactory();

    /**
     * The current configuration.
     */
    @NonNull
    Configuration configuration();

    /**
     * The consensus time to use for any state transitions initiated by this context.
     */
    @NonNull
    Instant now();

    /**
     * Checks if there are any dispatches remaining in the current context.
     * @return true if there are dispatches remaining, false otherwise
     */
    boolean hasDispatchesRemaining();

    /**
     * Dispatches a transaction to its handler on behalf of the network.
     *
     * @param payerId the account to pay for the transaction
     * @param spec the transaction to dispatch
     * @param functionality the functionality of the transaction
     */
    <T extends StreamBuilder> T dispatch(
            @NonNull AccountID payerId,
            @NonNull Consumer<TransactionBody.Builder> spec,
            @NonNull Class<T> streamBuilderType,
            @NonNull HederaFunctionality functionality);
}
