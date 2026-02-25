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
 * href="https://github.com/hashgraph/hedera-protobufs/blob/main/services/network_service.proto">Network
 * Service</a>.
 */
public interface NetworkService extends RpcService {
    String NAME = "NetworkService";

    @NonNull
    @Override
    default String getServiceName() {
        return NAME;
    }

    @NonNull
    @Override
    default Set<RpcServiceDefinition> rpcDefinitions() {
        return Set.of(NetworkServiceDefinition.INSTANCE);
    }

    /**
     * Returns the concrete implementation instance of the service.
     *
     * @return the implementation instance
     */
    @NonNull
    static NetworkService getInstance() {
        return ServiceFactory.loadService(NetworkService.class, ServiceLoader.load(NetworkService.class));
    }
}
