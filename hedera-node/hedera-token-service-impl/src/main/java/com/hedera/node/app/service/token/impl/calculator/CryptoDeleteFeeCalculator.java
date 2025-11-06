// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.node.app.service.token.impl.handlers.SimpleTxContextImpl;
import com.hedera.node.app.spi.fees.AbstractSimpleFeeCalculator;
import com.hedera.node.app.spi.fees.FeeContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.hapi.fees.FeeResult;

/** Calculates CryptoDelete fees. Per HIP-1261, only uses SIGNATURES extra. */
public class CryptoDeleteFeeCalculator extends AbstractSimpleFeeCalculator {

    @Override
    @NonNull
    public FeeResult calculateTxFee(@NonNull final FeeContext feeContext) {
        requireNonNull(feeContext, "feeContext");
        final var txContext = SimpleTxContextImpl.from(feeContext);

        // Build usage mapper - fluent API makes fee structure explicit
        final var usageMapper =
                usageBuilder().withSignatures(txContext.numTxnSignatures()).build();

        // Use base class template method for standard fee calculation
        return calculateStandardTxFee(HederaFunctionality.CRYPTO_DELETE, usageMapper, txContext);
    }
}
