// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.gas;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater.Enhancement;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A {@link DispatchGasCalculator} that calculates the gas requirement for the dispatch, given the transaction body,
 * payer, {@link SystemContractGasCalculator} and the {@link Enhancement} in the dispatch context
 */
@FunctionalInterface
public interface DispatchGasCalculator {
    /**
     * Given a transaction body to be dispatched, along with the effective payer, the
     * {@link SystemContractGasCalculator}, and the {@link Enhancement} in the dispatch context;
     * returns the gas requirement for the dispatch.
     *
     * @param body the transaction body to be dispatched
     * @param systemContractGasCalculator the {@link SystemContractGasCalculator} in the dispatch context
     * @param enhancement the {@link Enhancement} in the dispatch context
     * @param payerId the synthetic payer of the transaction
     * @return the gas requirement for the dispatch
     */
    long gasRequirement(
            @NonNull TransactionBody body,
            @NonNull SystemContractGasCalculator systemContractGasCalculator,
            @NonNull Enhancement enhancement,
            @NonNull AccountID payerId);
}
