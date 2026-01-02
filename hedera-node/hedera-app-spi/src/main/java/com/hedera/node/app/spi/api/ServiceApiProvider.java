// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.api;

import com.hedera.node.app.service.entityid.WritableEntityCounters;
import com.hedera.node.app.spi.fees.NodeFeeAccumulator;
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
     *  <i>(FUTURE)</i> Once HIP-1259, "Fee Collection Account", is enabled by default, we could replace
     *  {@code TokenServiceApiImpl}'s injected {@code NodeFeeAccumulator} by instead passing the
     *  {@code NodeFeeManager} to the existing {@code ObjLongConsumer<AccountID>} argument. (The current
     *  use of that argument to track <b>all</b> accumulated node fees for HIP-1065 could then be replaced
     *  by just summing the fees from all account ids in the {@code NodePayment} singleton.)
     *
     * @param configuration  the node configuration
     * @param writableStates the writable state of the service
     * @param entityCounters the entity counters
     * @param nodeFeeAccumulator the accumulator for node fees (used for in-memory fee accumulation)
     * @return the new API instance
     */
    T newInstance(
            @NonNull Configuration configuration,
            @NonNull WritableStates writableStates,
            @NonNull WritableEntityCounters entityCounters,
            @NonNull NodeFeeAccumulator nodeFeeAccumulator);
}
