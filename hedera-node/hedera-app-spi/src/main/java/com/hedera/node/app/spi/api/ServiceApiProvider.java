// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.api;

import com.hedera.node.app.service.entityid.WritableEntityCounters;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A factory for creating an API scoped to a specific service.
 *
 * @param <T> the type of the service API
 */
public interface ServiceApiProvider<T> {
    /**
     * Returns the name of the service whose writable state this API is scoped to.
     *
     * @return the name of the service
     */
    String serviceName();

    /**
     * Creates a new instance of the service API.
     *
     * @param configuration  the node configuration
     * @param writableStates the writable state of the service
     * @param entityCounters
     * @return the new API instance
     */
    T newInstance(
            @NonNull Configuration configuration,
            @NonNull WritableStates writableStates,
            @NonNull final WritableEntityCounters entityCounters);
}
