// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.CalculatorState;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

/** Calculates Token Create fees. */
public class TokenCreateFeeCalculator implements ServiceFeeCalculator {

    @Override
    public void accumulateServiceFee(
            @NonNull final TransactionBody txnBody,
            @Nullable final CalculatorState calculatorState,
            @NonNull final FeeResult feeResult,
            @NonNull final FeeSchedule feeSchedule) {
        final ServiceFeeDefinition serviceDef = lookupServiceFee(feeSchedule, HederaFunctionality.TOKEN_CREATE);
        feeResult.addServiceFee("Base Fee for " + HederaFunctionality.TOKEN_CREATE, 1, serviceDef.baseFee());
        addExtraFee(feeResult, serviceDef, Extra.SIGNATURES, feeSchedule, calculatorState.numTxnSignatures());
        var op = txnBody.tokenCreationOrThrow();
        long keys = 0;
        if (op.hasAdminKey()) {
            keys += 1;
        }
        if (op.hasFeeScheduleKey()) {
            keys += 1;
        }
        if (op.hasFreezeKey()) {
            keys += 1;
        }
        if (op.hasKycKey()) {
            keys += 1;
        }
        if (op.hasMetadataKey()) {
            keys += 1;
        }
        if (op.hasPauseKey()) {
            keys += 1;
        }
        if (op.hasSupplyKey()) {
            keys += 1;
        }
        if (op.hasWipeKey()) {
            keys += 1;
        }
        addExtraFee(feeResult, serviceDef, Extra.KEYS, feeSchedule, keys);

        if (op.tokenType() == TokenType.FUNGIBLE_COMMON) {
            addExtraFee(feeResult, serviceDef, Extra.STANDARD_FUNGIBLE_TOKENS, feeSchedule, 1);
        }
        if (op.tokenType() == TokenType.NON_FUNGIBLE_UNIQUE) {
            addExtraFee(feeResult, serviceDef, Extra.STANDARD_NON_FUNGIBLE_TOKENS, feeSchedule, 1);
        }
    }

    @Override
    public TransactionBody.DataOneOfType getTransactionType() {
        return TransactionBody.DataOneOfType.TOKEN_CREATION;
    }
}
