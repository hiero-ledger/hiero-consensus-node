// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.lifecycle;

import com.swirlds.config.api.Configuration;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;

/**
 * Provides context for post-upgrade setup performed by a {@link Service}.
 */
public interface PostUpgradeContext {
    /**
     * Returns the consensus time assigned to the post-upgrade setup work.
     */
    @NonNull
    Instant consensusTime();

    /**
     * Returns the active application configuration.
     */
    @NonNull
    Configuration configuration();

    /**
     * Returns the current round number where the post-upgrade setup work is being handled.
     */
    long roundNumber();

    /**
     * Returns the current network size.
     */
    int networkSize();

    /**
     * Returns readable states for the requested service.
     *
     * @param serviceName the service name
     * @return the requested readable states
     */
    @NonNull
    ReadableStates readableStates(@NonNull String serviceName);

    /**
     * Returns writable states for the requested service.
     *
     * @param serviceName the service name
     * @return the requested writable states
     */
    @NonNull
    WritableStates writableStates(@NonNull String serviceName);
}
