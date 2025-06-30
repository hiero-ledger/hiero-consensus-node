// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.api;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.contract.ContractNonceInfo;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Contains the ordered summary of the contracts added and the nonces updated by a contract operation.
 *
 * @param newContractIds the list of new contract IDs, ordered by contract number
 * @param updatedContractNonces the list of updated contract nonces, ordered by contract number
 */
public record ContractChangeSummary(List<ContractID> newContractIds, List<ContractNonceInfo> updatedContractNonces) {
    public static final Comparator<ContractID> CONTRACT_ID_NUM_COMPARATOR =
            Comparator.comparingLong(ContractID::contractNumOrThrow);
    public static final Comparator<ContractNonceInfo> NONCE_INFO_CONTRACT_ID_COMPARATOR =
            Comparator.comparing(ContractNonceInfo::contractIdOrThrow, CONTRACT_ID_NUM_COMPARATOR);

    /**
     * Constructs a new instance of the contract changes summary.
     * @param newContractIds the list of new contract IDs, ordered by contract number
     * @param updatedContractNonces the list of updated contract nonces, ordered by contract number
     */
    public ContractChangeSummary {
        Objects.requireNonNull(newContractIds).sort(CONTRACT_ID_NUM_COMPARATOR);
        Objects.requireNonNull(updatedContractNonces).sort(NONCE_INFO_CONTRACT_ID_COMPARATOR);
    }
}
