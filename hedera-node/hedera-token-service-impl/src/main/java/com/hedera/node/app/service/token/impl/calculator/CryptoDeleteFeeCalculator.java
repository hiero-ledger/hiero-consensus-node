// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import static java.util.Objects.requireNonNull;
import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.AbstractSimpleFeeCalculator;
import com.hedera.node.app.spi.fees.CalculatorState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

/** Calculates CryptoDelete fees. Per HIP-1261, only uses SIGNATURES extra. */
public class CryptoDeleteFeeCalculator extends AbstractSimpleFeeCalculator {

    public CryptoDeleteFeeCalculator(@NonNull final FeeSchedule feeSchedule) {
        super(feeSchedule);
    }

    @Override
    @NonNull
    public FeeResult calculateTxFee(@NonNull TransactionBody txBody, @Nullable final CalculatorState calculatorState) {
        requireNonNull(txBody, "feeContext");
        return calculateFeesForCryptoDelete(txBody, calculatorState);
    }

    /**
     * Calculates fees for CryptoDelete transactions per HIP-1261.
     * CryptoDelete uses only SIGNATURES extra for the service fee.
     *
     * @param txnBody the transaction body
     * @param calculatorState the calculator state containing signature count
     * @return the calculated fee result
     */
    private FeeResult calculateFeesForCryptoDelete(
            @NonNull final TransactionBody txnBody, @Nullable final CalculatorState calculatorState) {
        // Extract primitive counts (no allocations)
        final long signatures = calculatorState != null ? calculatorState.numTxnSignatures() : 0;
        final long bytes = TransactionBody.PROTOBUF.toBytes(txnBody).length();

        final var result = new FeeResult();

        // Add node base + extras
        result.addNodeFee("Node base fee", 1, feeSchedule.node().baseFee());
        addExtraFees(result, "Node", feeSchedule.node().extras(), signatures, bytes, 0);

        // Add network fee
        final int multiplier = feeSchedule.network().multiplier();
        result.addNetworkFee("Total Network fee", multiplier, result.node * multiplier);

        // Add service base + extras
        final ServiceFeeDefinition serviceDef = lookupServiceFee(feeSchedule, HederaFunctionality.CRYPTO_DELETE);
        result.addServiceFee("Base Fee for " + HederaFunctionality.CRYPTO_DELETE, 1, serviceDef.baseFee());
        addExtraFees(result, "Service", serviceDef.extras(), signatures, bytes, 0);

        return result;
    }
}
