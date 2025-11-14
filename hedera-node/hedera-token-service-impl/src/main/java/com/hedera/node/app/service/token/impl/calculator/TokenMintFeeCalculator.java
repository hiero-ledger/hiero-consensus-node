// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.CalculatorState;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

/** Calculates Token Mint fees*/
public class TokenMintFeeCalculator implements ServiceFeeCalculator {

    @Override
    public void accumulateServiceFee(
            @NonNull final TransactionBody txnBody,
            @Nullable final CalculatorState calculatorState,
            @NonNull final FeeResult feeResult,
            @NonNull final FeeSchedule feeSchedule) {
        final ServiceFeeDefinition serviceDef = lookupServiceFee(feeSchedule, HederaFunctionality.TOKEN_MINT);
        feeResult.addServiceFee("Base Fee for " + HederaFunctionality.TOKEN_MINT, 1, serviceDef.baseFee());
        var op = txnBody.tokenMintOrThrow();
        if (op.amount() > 0) {
            addExtraFee(feeResult, serviceDef, Extra.STANDARD_FUNGIBLE_TOKENS, feeSchedule, op.amount());
            addExtraFee(feeResult, serviceDef, Extra.STANDARD_NON_FUNGIBLE_TOKENS, feeSchedule, 0);
        } else {
            addExtraFee(feeResult, serviceDef, Extra.STANDARD_FUNGIBLE_TOKENS, feeSchedule, 0);
            addExtraFee(feeResult, serviceDef, Extra.STANDARD_NON_FUNGIBLE_TOKENS, feeSchedule, op.metadata().size());
        }
    }

    @Override
    public TransactionBody.DataOneOfType getTransactionType() {
        return TransactionBody.DataOneOfType.TOKEN_MINT;
    }
}
