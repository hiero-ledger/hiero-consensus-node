// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;
import static org.hiero.hapi.support.fees.Extra.ALLOWANCES;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

/** Calculates CryptoApproveAllowance fees */
public class CryptoApproveAllowanceFeeCalculator implements ServiceFeeCalculator {

    @Override
    public void accumulateServiceFee(
            @NonNull final TransactionBody txnBody,
            @Nullable final FeeContext feeContext,
            @NonNull final FeeResult feeResult,
            @NonNull final FeeSchedule feeSchedule) {
        final var op = txnBody.cryptoApproveAllowanceOrThrow();
        final ServiceFeeDefinition serviceDef =
                lookupServiceFee(feeSchedule, HederaFunctionality.CRYPTO_APPROVE_ALLOWANCE);
        feeResult.setServiceBaseFeeTinycents(serviceDef.baseFee());

        final int totalAllowances = op.cryptoAllowances().size()
                + op.tokenAllowances().size()
                + op.nftAllowances().size();

        if (totalAllowances > 0) {
            addExtraFee(feeResult, serviceDef, ALLOWANCES, feeSchedule, totalAllowances);
        }
    }

    @Override
    public TransactionBody.DataOneOfType getTransactionType() {
        return TransactionBody.DataOneOfType.CRYPTO_APPROVE_ALLOWANCE;
    }
}
