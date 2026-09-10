// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr;

import com.hedera.node.app.spi.RpcService;
import com.hedera.node.app.spi.ServiceFactory;
import com.hedera.pbj.runtime.RpcServiceDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * The CLPR (Cross Ledger Protocol) service manages cross-ledger channels
 * and messaging between independent ledger networks.
 */
public interface ClprService extends RpcService {

    /**
     * The name of the service.
     */
    String NAME = "ClprService";

    @NonNull
    @Override
    default String getServiceName() {
        return NAME;
    }

    @NonNull
    @Override
    default Set<RpcServiceDefinition> rpcDefinitions() {
        return Set.of(ClprEndpointServiceDefinition.INSTANCE, ClprTransactionServiceDefinition.INSTANCE);
    }

    /**
     * Returns the concrete implementation instance of the service.
     *
     * @return the implementation instance
     */
    @NonNull
    static ClprService getInstance() {
        return ServiceFactory.loadService(ClprService.class, ServiceLoader.load(ClprService.class));
    }
}
