// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.lifecycle;

import com.swirlds.config.api.Configuration;
import com.swirlds.state.spi.ReadableStates;
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
}
