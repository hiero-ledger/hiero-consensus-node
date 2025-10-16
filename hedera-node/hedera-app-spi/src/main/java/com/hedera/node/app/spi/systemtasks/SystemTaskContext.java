// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.systemtasks;

import com.hedera.hapi.node.state.systemtask.SystemTask;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.store.StoreFactory;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;

/**
 * Provides context for handling a {@link SystemTask}, including access to
 * configuration, network info, time, and stores; and a way to enqueue
 * additional system tasks.
 */
public interface SystemTaskContext {
    /**
     * The system task being handled.
     */
    @NonNull
    SystemTask currentTask();

    /**
     * Enqueue a new system task to be processed asynchronously.
     * Implementations must ensure the task is added to the SystemTaskService
     * writable queue for the current state, so it is committed atomically
     * with other state changes.
     *
     * @param task the task to enqueue
     */
    void offerSystemTask(@NonNull SystemTask task);

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
     * Information about the current network.
     */
    @NonNull
    NetworkInfo networkInfo();

    /**
     * The consensus time to use for any state transitions initiated by this context.
     */
    @NonNull
    Instant now();
}
