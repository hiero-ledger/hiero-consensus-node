// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.util;

import com.hedera.node.app.spi.RpcService;
import com.hedera.node.app.spi.ServiceFactory;
import com.hedera.pbj.runtime.RpcServiceDefinition;
import com.swirlds.state.lifecycle.SchemaRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Implements the HAPI <a
 * href="https://github.com/hashgraph/hedera-protobufs/blob/main/services/util_service.proto">Util
 * Service</a>.
 */
public interface UtilService extends RpcService {

    String NAME = "UtilService";

    @NonNull
    @Override
    default String getServiceName() {
        return NAME;
    }

    @NonNull
    @Override
    default Set<RpcServiceDefinition> rpcDefinitions() {
        return Set.of(UtilServiceDefinition.INSTANCE);
    }

    /**
     * Returns the concrete implementation instance of the service.
     *
     * @return the implementation instance
     */
    @NonNull
    static UtilService getInstance() {
        return ServiceFactory.loadService(UtilService.class, ServiceLoader.load(UtilService.class));
    }

    @Override
    default void registerSchemas(@NonNull SchemaRegistry registry) {
        // no schemas to register
    }
}
