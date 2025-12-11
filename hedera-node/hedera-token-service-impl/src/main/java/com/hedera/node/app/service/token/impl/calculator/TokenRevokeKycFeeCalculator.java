// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.config.data.EntitiesConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

public class TokenRevokeKycFeeCalculator implements ServiceFeeCalculator {

    @Override
    public void accumulateServiceFee(
            @NonNull final TransactionBody txnBody,
            @Nullable final FeeContext feeContext,
            @NonNull final FeeResult feeResult,
            @NonNull final org.hiero.hapi.support.fees.FeeSchedule feeSchedule) {
        final var op = txnBody.tokenRevokeKycOrThrow();

        final var unlimitedAssociationsEnabled =
                feeContext.configuration().getConfigData(EntitiesConfig.class).unlimitedAutoAssociationsEnabled();

        // Add service base + extras
        final ServiceFeeDefinition serviceDef =
                lookupServiceFee(feeSchedule, HederaFunctionality.TOKEN_REVOKE_KYC_FROM_ACCOUNT);
        feeResult.addServiceFee(1, serviceDef.baseFee());
        if (!unlimitedAssociationsEnabled) {
            throw new Error("the not unlimited associations case is not handled for simple fees yet.");
        }
    }

    public TransactionBody.DataOneOfType getTransactionType() {
        return TransactionBody.DataOneOfType.TOKEN_REVOKE_KYC;
    }
}
