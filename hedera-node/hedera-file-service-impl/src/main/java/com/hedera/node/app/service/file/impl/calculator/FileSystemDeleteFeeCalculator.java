// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file.impl.calculator;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.FeeSchedule;

/**
 * Fee calculator for {@link HederaFunctionality#SYSTEM_DELETE} transactions.
 */
public class FileSystemDeleteFeeCalculator implements ServiceFeeCalculator {
    @Override
    public void accumulateServiceFee(
            @NonNull final TransactionBody txnBody,
            @NonNull SimpleFeeContext simpleFeeContext,
            @NonNull final FeeResult feeResult,
            @NonNull final FeeSchedule feeSchedule) {
        // SystemDelete is a privileged operation; we clear the node and network fee
        // previously set by SimpleFeeCalculatorImpl, and baseFee is 0 with no extras.
        feeResult.clearFees();
    }

    @Override
    public TransactionBody.DataOneOfType getTransactionType() {
        return TransactionBody.DataOneOfType.SYSTEM_DELETE;
    }
}
