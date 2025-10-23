// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr;

import com.hedera.node.app.spi.RpcService;
import com.hedera.node.app.spi.ServiceFactory;
import com.hedera.pbj.runtime.RpcServiceDefinition;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Implements the HAPI <a
 * href="https://github.com/hashgraph/hedera-protobufs/blob/main/interledger/clpr_service.proto">CLPR
 * Service</a>.
 */
public interface ClprService extends RpcService {
    /**
     * The name of the service.
     */
    String NAME = "ClprService";

    /**
     * A functional interface for dispatching a transaction to update the local ledger configuration.
     */
    @FunctionalInterface
    interface LedgerConfigurationDispatcher {
        void dispatch(@NonNull State state, @NonNull Instant consensusTime);
    }

    /**
     * Sets the dispatcher for updating the local ledger configuration.
     *
     * @param dispatcher The dispatcher to set.
     */
    void setTransactionDispatcher(@NonNull LedgerConfigurationDispatcher dispatcher);

    /**
     * Dispatches a transaction to update the local ledger configuration.
     *
     * @param state The current state.
     * @param consensusTime The consensus time.
     */
    void dispatchLedgerConfigurationUpdate(@NonNull State state, @NonNull Instant consensusTime);

    @NonNull
    @Override
    default String getServiceName() {
        return NAME;
    }

    @NonNull
    @Override
    default Set<RpcServiceDefinition> rpcDefinitions() {
        return Set.of(ClprServiceDefinition.INSTANCE);
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
