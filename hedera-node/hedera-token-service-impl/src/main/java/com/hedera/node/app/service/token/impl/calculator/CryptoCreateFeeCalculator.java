// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import static com.hedera.node.app.spi.fees.SimpleFeeCalculatorImpl.countKeys;
import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;
import static org.hiero.hapi.support.fees.Extra.KEYS;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.CalculatorState;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

/** Calculates CryptoCreate fees. Per HIP-1261, uses SIGNATURES and KEYS extras. */
public class CryptoCreateFeeCalculator implements ServiceFeeCalculator {

    @Override
    public void accumulateServiceFee(
            @NonNull final TransactionBody txnBody,
            @Nullable final CalculatorState calculatorState,
            @NonNull final FeeResult feeResult,
            @NonNull final org.hiero.hapi.support.fees.FeeSchedule feeSchedule) {
        final var key = txnBody.cryptoCreateAccountOrThrow().key();
        final long keys = key != null ? countKeys(key) : 0;
        // Add service base + extras
        final ServiceFeeDefinition serviceDef = lookupServiceFee(feeSchedule, HederaFunctionality.CRYPTO_CREATE);
        feeResult.addServiceFee("Base Fee for " + HederaFunctionality.CRYPTO_CREATE, 1, serviceDef.baseFee());

        // Add KEYS extra, respecting includedCount
        final var keysExtra = serviceDef.extras().stream()
                .filter(ref -> ref.name() == KEYS)
                .findFirst();
        if (keysExtra.isPresent()) {
            final long includedCount = keysExtra.get().includedCount();
            final long chargedKeys = Math.max(0, keys - includedCount);
            if (chargedKeys > 0) {
                final long keyFee = org.hiero.hapi.fees.FeeScheduleUtils.lookupExtraFee(feeSchedule, KEYS).fee();
                feeResult.addServiceFee("Extra KEYS", chargedKeys, keyFee);
            }
        }
    }

    public TransactionBody.DataOneOfType getTransactionType() {
        return TransactionBody.DataOneOfType.CRYPTO_CREATE_ACCOUNT;
    }
}
