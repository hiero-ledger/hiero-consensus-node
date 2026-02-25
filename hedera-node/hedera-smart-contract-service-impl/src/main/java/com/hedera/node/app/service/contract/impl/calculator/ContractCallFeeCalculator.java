// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.calculator;

import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.FeeSchedule;

public class ContractCallFeeCalculator implements ServiceFeeCalculator {
    @Override
    public void accumulateServiceFee(
            @NonNull final TransactionBody txnBody,
            @NonNull final SimpleFeeContext simpleFeeContext,
            @NonNull final FeeResult feeResult,
            @NonNull final FeeSchedule feeSchedule) {
        // we clear the node and network fee previously set by SimpleFeeCalculatorImpl,
        // as contract call is paid only in gas
        feeResult.clearFees();
    }

    @Override
    public TransactionBody.DataOneOfType getTransactionType() {
        return TransactionBody.DataOneOfType.CONTRACT_CALL;
    }
}
