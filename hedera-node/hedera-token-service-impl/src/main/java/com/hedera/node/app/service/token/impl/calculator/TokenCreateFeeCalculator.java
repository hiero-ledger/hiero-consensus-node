// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import static org.hiero.hapi.fees.FeeKeyUtils.countKeys;
import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

/** Calculates Token Create fees. */
public class TokenCreateFeeCalculator implements ServiceFeeCalculator {

    @Override
    public void accumulateServiceFee(
            @NonNull final TransactionBody txnBody,
            @NonNull SimpleFeeContext simpleFeeContext,
            @NonNull final FeeResult feeResult,
            @NonNull final FeeSchedule feeSchedule) {
        final ServiceFeeDefinition serviceDef = lookupServiceFee(feeSchedule, HederaFunctionality.TOKEN_CREATE);
        feeResult.setServiceBaseFeeTinycents(serviceDef.baseFee());
        var op = txnBody.tokenCreationOrThrow();
        long keys = 0;
        if (op.hasAdminKey()) {
            keys += countKeys(op.adminKey());
        }
        if (op.hasFeeScheduleKey()) {
            keys += countKeys(op.feeScheduleKey());
        }
        if (op.hasFreezeKey()) {
            keys += countKeys(op.freezeKey());
        }
        if (op.hasKycKey()) {
            keys += countKeys(op.kycKey());
        }
        if (op.hasMetadataKey()) {
            keys += countKeys(op.metadataKey());
        }
        if (op.hasPauseKey()) {
            keys += countKeys(op.pauseKey());
        }
        if (op.hasSupplyKey()) {
            keys += countKeys(op.supplyKey());
        }
        if (op.hasWipeKey()) {
            keys += countKeys(op.wipeKey());
        }
        addExtraFee(feeResult, serviceDef, Extra.KEYS, feeSchedule, keys);

        if (!op.customFees().isEmpty()) {
            addExtraFee(feeResult, serviceDef, Extra.TOKEN_CREATE_WITH_CUSTOM_FEE, feeSchedule, 1);
        }
    }

    @Override
    public TransactionBody.DataOneOfType getTransactionType() {
        return TransactionBody.DataOneOfType.TOKEN_CREATION;
    }
}
