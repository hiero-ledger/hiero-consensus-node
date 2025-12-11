package com.hedera.node.app.service.token.impl.calculator;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.config.data.EntitiesConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;

public class TokenAssociateFeeCalculator implements ServiceFeeCalculator {

    @Override
    public void accumulateServiceFee(
            @NonNull final TransactionBody txnBody,
            @Nullable final FeeContext feeContext,
            @NonNull final FeeResult feeResult,
            @NonNull final org.hiero.hapi.support.fees.FeeSchedule feeSchedule) {
        final var op = txnBody.tokenAssociateOrThrow();
        final var unlimitedAssociationsEnabled =
                feeContext.configuration().getConfigData(EntitiesConfig.class).unlimitedAutoAssociationsEnabled();

        // Add service base + extras
        final ServiceFeeDefinition serviceDef = lookupServiceFee(feeSchedule, HederaFunctionality.TOKEN_ASSOCIATE_TO_ACCOUNT);
        feeResult.addServiceFee(1, serviceDef.baseFee());
        if(!unlimitedAssociationsEnabled) {
            throw new Error("the not unlimited associations case is not handled for simple fees yet.");
        }
    }

    public TransactionBody.DataOneOfType getTransactionType() {
        return TransactionBody.DataOneOfType.TOKEN_ASSOCIATE;
    }
}

