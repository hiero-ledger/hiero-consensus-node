// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi;

import com.hedera.node.app.spi.systemtasks.SystemTaskHandler;
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
     * If this service can handle system tasks, then this method returns the system task handlers.
     * @return The system task handlers if this service can handle system tasks.
     */
    default Set<SystemTaskHandler> systemTaskHandlers() {
        return Set.of();
    }
}
