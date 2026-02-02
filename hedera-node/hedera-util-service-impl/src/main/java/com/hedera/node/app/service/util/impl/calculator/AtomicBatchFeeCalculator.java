// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.util.impl.calculator;

import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

/**
 * Fee calculator for {@link HederaFunctionality#ATOMIC_BATCH} transactions.
 */
public class AtomicBatchFeeCalculator implements ServiceFeeCalculator {
    @Override
    public void accumulateServiceFee(
            @NonNull final TransactionBody txnBody,
            @NonNull final SimpleFeeContext feeContext,
            @NonNull final FeeResult feeResult,
            @NonNull final FeeSchedule feeSchedule) {
        final ServiceFeeDefinition serviceDef = lookupServiceFee(feeSchedule, HederaFunctionality.ATOMIC_BATCH);
        feeResult.setServiceBaseFeeTinycents(serviceDef.baseFee());
    }

    @Override
    public TransactionBody.DataOneOfType getTransactionType() {
        return TransactionBody.DataOneOfType.ATOMIC_BATCH;
    }
}
