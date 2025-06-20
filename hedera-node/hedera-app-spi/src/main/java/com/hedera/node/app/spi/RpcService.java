// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi;

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
}
