// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.AbstractSimpleFeeCalculator;
import com.hedera.node.app.spi.fees.CalculatorState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.FeeSchedule;

/** Calculates CryptoCreate fees. Per HIP-1261, uses SIGNATURES and KEYS extras. */
public class CryptoCreateFeeCalculator extends AbstractSimpleFeeCalculator {

    public CryptoCreateFeeCalculator(FeeSchedule feeSchedule) {
        super(feeSchedule);
    }

    @Override
    @NonNull
    public FeeResult calculateTxFee(@NonNull TransactionBody txBody, @Nullable final CalculatorState calculatorState) {
        requireNonNull(calculatorState, "calculatorState");
        return calculateStandardTxFee(HederaFunctionality.CRYPTO_CREATE, txBody, calculatorState);
    }
}
