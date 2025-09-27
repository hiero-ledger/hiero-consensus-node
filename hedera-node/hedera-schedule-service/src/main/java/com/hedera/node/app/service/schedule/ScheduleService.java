// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule;

import com.hedera.node.app.spi.RpcService;
import com.hedera.node.app.spi.ServiceFactory;
import com.hedera.node.app.spi.api.ServiceApiProvider;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.pbj.runtime.RpcServiceDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Implements the HAPI <a
 * href="https://github.com/hashgraph/hedera-protobufs/blob/main/services/schedule_service.proto">Schedule
 * Service</a>.
 */
public interface ScheduleService extends RpcService {

    String NAME = "ScheduleService";

    @NonNull
    @Override
    default String getServiceName() {
        return NAME;
    }

    /**
     * Returns the RPC definitions for the service.
     *
     * @return the RPC definitions
     */
    @NonNull
    @Override
    default Set<RpcServiceDefinition> rpcDefinitions() {
        return Set.of(ScheduleServiceDefinition.INSTANCE);
    }

    /**
     * Returns the concrete implementation instance of the service.
     *
     * @return the implementation instance
     */
    @NonNull
    static ScheduleService getInstance() {
        return ServiceFactory.loadService(ScheduleService.class, ServiceLoader.load(ScheduleService.class));
    }

    /**
     * Given the endpoints of a closed interval in consensus time, returns an iterator over all
     * {@link ExecutableTxn} instances that this service wants to execute in the interval. The
     * given {@link StoreFactory} is used to access the state of the service to discover these
     * executable transactions; and the returned iterator can use the same instance to remove
     * any state that is no longer needed after the last-returned transaction has been executed.
     *
     * @param start the start of the interval
     * @param end the end of the interval
     * @param storeFactory the factory for creating service stores
     * @return an iterator over all transactions that should be executed in the interval
     */
    ExecutableTxnIterator executableTxns(
            @NonNull Instant start, @NonNull Instant end, @NonNull StoreFactory storeFactory);

    /**
     * Returns the API provider for the schedule service.
     */
    ServiceApiProvider<ScheduleServiceApi> apiProvider();
}
