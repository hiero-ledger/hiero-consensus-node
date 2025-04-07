// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract;

import com.hedera.node.app.spi.RpcService;
import com.hedera.pbj.runtime.RpcServiceDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Implements the HAPI <a
 * href="https://github.com/hashgraph/hedera-protobufs/blob/main/services/smart_contract_service.proto">Smart
 * Contract Service</a>.
 */
public interface ContractService extends RpcService {
    /**
     * The name of the service.
     */
    String NAME = "ContractService";

    /**
     * {@inheritDoc}
     *
     * Ensure the contract service schemas are migrated before the {@code TokenService} schemas, since
     * the {@code TokenService} depends on the {@code ContractService} to know the updated first storage
     * keys for contracts with broken storage links.
     */
    @Override
    default int migrationOrder() {
        return -1;
    }

    @NonNull
    @Override
    default String getServiceName() {
        return NAME;
    }

    @NonNull
    @Override
    default Set<RpcServiceDefinition> rpcDefinitions() {
        return Set.of(SmartContractServiceDefinition.INSTANCE);
    }
}
