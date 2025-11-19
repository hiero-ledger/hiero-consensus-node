// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import static com.hedera.node.app.spi.fees.SimpleFeeCalculatorImpl.countKeys;
import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;
import static org.hiero.hapi.support.fees.Extra.HOOKS;
import static org.hiero.hapi.support.fees.Extra.KEYS;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.CalculatorState;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

/** Calculates CryptoCreate fees */
public class CryptoCreateFeeCalculator implements ServiceFeeCalculator {

    private static final Logger log = LogManager.getLogger(CryptoCreateFeeCalculator.class);

    @Override
    public void accumulateServiceFee(
            @NonNull final TransactionBody txnBody,
            @Nullable final CalculatorState calculatorState,
            @NonNull final FeeResult feeResult,
            @NonNull final org.hiero.hapi.support.fees.FeeSchedule feeSchedule) {
        final var op = txnBody.cryptoCreateAccountOrThrow();
        // Add service base + extras
        final ServiceFeeDefinition serviceDef = lookupServiceFee(feeSchedule, HederaFunctionality.CRYPTO_CREATE);
        log.info("SIMPLE_FEE_DEBUG CryptoCreate - baseFee: {}, extras: {}", serviceDef.baseFee(), serviceDef.extras());
        feeResult.addServiceFee(1, serviceDef.baseFee());
        if (op.hasKey()) {
            addExtraFee(feeResult, serviceDef, KEYS, feeSchedule, countKeys(op.key()));
        }
        if (!op.hookCreationDetails().isEmpty()) {
            log.info("SIMPLE_FEE_DEBUG CryptoCreate - adding HOOKS fee for {} hooks", op.hookCreationDetails().size());
            addExtraFee(feeResult, serviceDef, HOOKS, feeSchedule, op.hookCreationDetails().size());
        }
    }

    public TransactionBody.DataOneOfType getTransactionType() {
        return TransactionBody.DataOneOfType.CRYPTO_CREATE_ACCOUNT;
    }
}
