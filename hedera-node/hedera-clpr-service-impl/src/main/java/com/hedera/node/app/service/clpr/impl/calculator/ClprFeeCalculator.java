// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.calculator;

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
 * A single fee calculator for all CLPR transaction types. Each instance is parameterized
 * by the {@link HederaFunctionality} and {@link TransactionBody.DataOneOfType} it handles.
 *
 * @param functionality the CLPR functionality for fee schedule lookup
 * @param transactionType the transaction body discriminant this calculator handles
 */
public record ClprFeeCalculator(
        @NonNull HederaFunctionality functionality, @NonNull TransactionBody.DataOneOfType transactionType)
        implements ServiceFeeCalculator {
    @Override
    public void accumulateServiceFee(
            @NonNull final TransactionBody txnBody,
            @NonNull final SimpleFeeContext simpleFeeContext,
            @NonNull final FeeResult feeResult,
            @NonNull final FeeSchedule feeSchedule) {
        final ServiceFeeDefinition serviceDef = lookupServiceFee(feeSchedule, functionality);
        if (serviceDef != null) {
            feeResult.setServiceBaseFeeTinycents(serviceDef.baseFee());
        }
    }

    @Override
    public TransactionBody.DataOneOfType getTransactionType() {
        return transactionType;
    }
}
