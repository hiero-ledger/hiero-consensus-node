// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.api;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Public API for reading smart contract state from other services.
 */
public interface SmartContractServiceApi {

    /**
     * Returns the deployed bytecode for the given contract, or {@code null} if no bytecode
     * is stored for that contract ID.
     *
     * @param contractId the contract whose bytecode to retrieve
     * @return the raw bytecode bytes, or {@code null} if not found
     */
    @Nullable
    Bytes getContractBytecode(@NonNull ContractID contractId);
}
