// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin;

import com.hedera.node.app.spi.RpcService;
import com.hedera.node.app.spi.ServiceFactory;
import com.hedera.pbj.runtime.RpcServiceDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Implements the HAPI <a
 * href="https://github.com/hashgraph/hedera-protobufs/blob/main/services/freeze_service.proto">Freeze
 * Service</a>.
 */
public interface FreezeService extends RpcService {

    String NAME = "FreezeService";

    /**
     * Returns the service name.
     * @return the service name
     */
    @NonNull
    @Override
    default String getServiceName() {
        return NAME;
    }

    /**
     * Returns the RPC definitions for the service.
     * @return the RPC definitions
     */
    @NonNull
    @Override
    default Set<RpcServiceDefinition> rpcDefinitions() {
        return Set.of(FreezeServiceDefinition.INSTANCE);
    }

    /**
     * Returns the concrete implementation instance of the service.
     *
     * @return the implementation instance
     */
    @NonNull
    static FreezeService getInstance() {
        return ServiceFactory.loadService(FreezeService.class, ServiceLoader.load(FreezeService.class));
    }
}
