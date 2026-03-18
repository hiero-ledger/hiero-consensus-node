// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.gas;

import com.hedera.node.app.hapi.utils.ethereum.AccessList;
import com.hedera.node.app.hapi.utils.ethereum.CodeDelegation;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Transaction;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

public interface HederaGasCalculator extends GasCalculator {

    /**
     * Calculate gas requirements of the transaction.
     * This method mirrors {{@link GasCalculator#transactionIntrinsicGasCost(Transaction, long)},
     * but does not require a full Transaction object and uses
     * {@link GasCalculator#transactionFloorCost(Bytes, long)} for `minimumGasUsed` calculation.
     *
     * @param payload          the payload of the transaction
     * @param isContractCreate is this call a 'contract creation'
     * @param accessLists       the accessList of the transaction
     * @param codeDelegations  the authorizationList of the type 4 transaction
     * @return The gas requirements of the transaction
     */
    GasCharges transactionGasRequirements(
            @NonNull final Bytes payload,
            final boolean isContractCreate,
            @Nullable final List<AccessList> accessLists,
            @Nullable final List<CodeDelegation> codeDelegations);
}
