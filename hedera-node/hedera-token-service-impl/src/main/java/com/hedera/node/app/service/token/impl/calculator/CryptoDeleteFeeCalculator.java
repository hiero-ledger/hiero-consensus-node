// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.AbstractSimpleFeeCalculator;
import com.hedera.node.app.spi.fees.CalculatorState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.FeeSchedule;

import static java.util.Objects.requireNonNull;

/** Calculates CryptoDelete fees. Per HIP-1261, only uses SIGNATURES extra. */
public class CryptoDeleteFeeCalculator extends AbstractSimpleFeeCalculator {

    public CryptoDeleteFeeCalculator(@NonNull final FeeSchedule feeSchedule) {
        super(feeSchedule);
    }

    @Override
    @NonNull
    public FeeResult calculateTxFee(@NonNull TransactionBody txBody, @Nullable final CalculatorState calculatorState) {
        requireNonNull(txBody, "feeContext");
        return calculateStandardTxFee(HederaFunctionality.CRYPTO_DELETE, txBody, calculatorState);
    }
}
