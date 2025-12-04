// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.contract.ContractService;
import com.hedera.node.app.service.contract.ContractServiceApi;
import com.hedera.node.app.service.contract.impl.state.WritableContractStateStore;
import com.hedera.node.app.service.entityid.WritableEntityCounters;
import com.hedera.node.app.spi.api.ServiceApiProvider;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Singleton;
import org.jetbrains.annotations.NotNull;

@Singleton
public class ContractServiceApiProvider implements ServiceApiProvider<ContractServiceApi> {

    @Override
    public String serviceName() {
        return ContractService.NAME;
    }

    @Override
    public ContractServiceApi newInstance(
            @NonNull final Configuration configuration,
            @NonNull final WritableStates writableStates,
            @NonNull final WritableEntityCounters entityCounters) {
        requireNonNull(configuration);
        requireNonNull(writableStates);
        requireNonNull(entityCounters);
        return new ContractServiceApiImpl(new WritableContractStateStore(writableStates, entityCounters));
    }

    /**
     * Default implementation of the {@link ContractServiceApi} interface.
     */
    public class ContractServiceApiImpl implements ContractServiceApi {
        private final WritableContractStateStore contractStateStore;

        public ContractServiceApiImpl(@NonNull final WritableContractStateStore contractStateStore) {
            this.contractStateStore = requireNonNull(contractStateStore);
        }

        @Override
        public void setAccountBytecode(@NotNull AccountID accountID, @NotNull Bytes bytecode) {
            final var contractID = ContractID.newBuilder()
                    .shardNum(accountID.shardNum())
                    .realmNum(accountID.realmNum())
                    .contractNum(accountID.accountNumOrThrow())
                    .build();
            contractStateStore.putBytecode(
                    contractID, new com.hedera.hapi.node.state.contract.Bytecode(requireNonNull(bytecode)));
        }
    }
}
