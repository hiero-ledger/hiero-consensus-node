// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.api;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.contract.api.SmartContractServiceApi;
import com.hedera.node.app.service.contract.impl.state.ReadableContractStateStore;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Default implementation of {@link SmartContractServiceApi}.
 */
public class SmartContractServiceApiImpl implements SmartContractServiceApi {

    private final ReadableContractStateStore contractStateStore;

    public SmartContractServiceApiImpl(@NonNull final ReadableContractStateStore contractStateStore) {
        this.contractStateStore = requireNonNull(contractStateStore);
    }

    @Override
    @Nullable
    public Bytes getContractBytecode(@NonNull final ContractID contractId) {
        requireNonNull(contractId);
        final var bytecode = contractStateStore.getBytecode(contractId);
        return bytecode == null ? null : bytecode.code();
    }
}
