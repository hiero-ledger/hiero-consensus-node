// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.api;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.ContractService;
import com.hedera.node.app.service.contract.api.SmartContractServiceApi;
import com.hedera.node.app.service.contract.impl.state.ReadableContractStateStore;
import com.hedera.node.app.service.entityid.WritableEntityCounters;
import com.hedera.node.app.spi.api.ServiceApiProvider;
import com.hedera.node.app.spi.fees.NodeFeeAccumulator;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Provides instances of {@link SmartContractServiceApi} scoped to a given writable state.
 */
public class SmartContractServiceApiProvider implements ServiceApiProvider<SmartContractServiceApi> {

    public static final SmartContractServiceApiProvider SMART_CONTRACT_SERVICE_API_PROVIDER =
            new SmartContractServiceApiProvider();

    @Override
    public String serviceName() {
        return ContractService.NAME;
    }

    @Override
    public SmartContractServiceApi newInstance(
            @NonNull final Configuration configuration,
            @NonNull final WritableStates writableStates,
            @NonNull final WritableEntityCounters entityCounters,
            @NonNull final NodeFeeAccumulator nodeFeeAccumulator) {
        requireNonNull(writableStates);
        requireNonNull(entityCounters);
        return new SmartContractServiceApiImpl(new ReadableContractStateStore(writableStates, entityCounters));
    }
}
