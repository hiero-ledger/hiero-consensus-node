// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

/** Calculates Token Delete fees */
public class TokenDeleteFeeCalculator implements ServiceFeeCalculator {

    @Override
    public void accumulateServiceFee(
            @NonNull final TransactionBody txnBody,
            @Nullable final FeeContext feeContext,
            @NonNull final FeeResult feeResult,
            @NonNull final org.hiero.hapi.support.fees.FeeSchedule feeSchedule) {
        final var op = txnBody.tokenDeletionOrThrow();
        final ServiceFeeDefinition serviceDef = lookupServiceFee(feeSchedule, HederaFunctionality.TOKEN_DELETE);
        feeResult.setServiceBaseFeeTinycents(serviceDef.baseFee());
    }

    public TransactionBody.DataOneOfType getTransactionType() {
        return TransactionBody.DataOneOfType.TOKEN_DELETION;
    }
}
