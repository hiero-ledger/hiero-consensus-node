// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import static com.hedera.node.app.spi.fees.SimpleFeeCalculatorImpl.countKeys;
import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;
import static org.hiero.hapi.support.fees.Extra.HOOK_UPDATES;
import static org.hiero.hapi.support.fees.Extra.KEYS;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

/** Calculates CryptoUpdate fees */
public class CryptoUpdateFeeCalculator implements ServiceFeeCalculator {

    @Override
    public void accumulateServiceFee(
            @NonNull final TransactionBody txnBody,
            @Nullable final FeeContext feeContext,
            @NonNull final FeeResult feeResult,
            @NonNull final org.hiero.hapi.support.fees.FeeSchedule feeSchedule,
            EstimationMode mode) {
        final var op = txnBody.cryptoUpdateAccountOrThrow();
        final ServiceFeeDefinition serviceDef = lookupServiceFee(feeSchedule, HederaFunctionality.CRYPTO_UPDATE);
        feeResult.addServiceFee(1, serviceDef.baseFee());
        if (op.hasKey()) {
            addExtraFee(feeResult, serviceDef, KEYS, feeSchedule, countKeys(op.key()));
        }
        final int hookOperations =
                op.hookCreationDetails().size() + op.hookIdsToDelete().size();
        if (hookOperations > 0) {
            addExtraFee(feeResult, serviceDef, HOOK_UPDATES, feeSchedule, hookOperations);
        }
    }

    @Override
    public TransactionBody.DataOneOfType getTransactionType() {
        return TransactionBody.DataOneOfType.CRYPTO_UPDATE_ACCOUNT;
    }
}
