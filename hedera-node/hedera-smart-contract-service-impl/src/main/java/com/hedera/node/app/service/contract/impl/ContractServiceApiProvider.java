// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl;

import static java.util.Objects.requireNonNull;
import static org.hyperledger.besu.evm.worldstate.CodeDelegationHelper.CODE_DELEGATION_PREFIX;

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
        public void setAccountDelegationTarget(@NonNull final AccountID accountID, @NonNull final Bytes bytecode) {
            requireNonNull(accountID);
            requireNonNull(bytecode);
            final var contractID = ContractID.newBuilder()
                    .shardNum(accountID.shardNum())
                    .realmNum(accountID.realmNum())
                    .contractNum(accountID.accountNumOrThrow())
                    .build();
            if (bytecode.equals(Bytes.EMPTY)) {
                // Remove the bytecode if the bytecode is empty
                contractStateStore.removeBytecode(contractID);
            } else {
                final var delegationIndicator = Bytes.merge(Bytes.wrap(CODE_DELEGATION_PREFIX.toArray()), bytecode);
                contractStateStore.putBytecode(
                        contractID, new com.hedera.hapi.node.state.contract.Bytecode(delegationIndicator));
            }
        }
    }
}
