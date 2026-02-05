// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

public class TokenGrantKycFeeCalculator implements ServiceFeeCalculator {

    @Override
    public void accumulateServiceFee(
            @NonNull final TransactionBody txnBody,
            @Nullable final SimpleFeeContext feeContext,
            @NonNull final FeeResult feeResult,
            @NonNull final FeeSchedule feeSchedule) {
        // Add service base + extras
        final ServiceFeeDefinition serviceDef =
                lookupServiceFee(feeSchedule, HederaFunctionality.TOKEN_GRANT_KYC_TO_ACCOUNT);
        feeResult.setServiceBaseFeeTinycents(serviceDef.baseFee());
    }

    public TransactionBody.DataOneOfType getTransactionType() {
        return TransactionBody.DataOneOfType.TOKEN_GRANT_KYC;
    }
}
