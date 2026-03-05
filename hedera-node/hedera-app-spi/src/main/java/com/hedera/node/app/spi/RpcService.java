// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi;

import com.hedera.node.app.spi.fees.QueryFeeCalculator;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.pbj.runtime.RpcServiceDefinition;
import com.swirlds.state.lifecycle.Service;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * This interface defines the contract for a service that can expose RPC endpoints.
 */
public interface RpcService extends Service {

    /**
     * If this service exposes RPC endpoints, then this method returns the RPC service definitions.
     *
     * @return The RPC service definitions if this service is exposed via RPC.
     */
    @NonNull
    Set<RpcServiceDefinition> rpcDefinitions();

    /**
     * Returns all the handlers fee calculators for this service.
     *
     * @return The set of fee calculators.
     */
    default Set<ServiceFeeCalculator> serviceFeeCalculators() {
        return Set.of();
    }

    /**
     * Returns all the query fee calculators for this service.
     *
     * @return The set of fee calculators.
     */
    default Set<QueryFeeCalculator> queryFeeCalculators() {
        return Set.of();
    }
}
