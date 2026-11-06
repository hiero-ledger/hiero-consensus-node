// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.calculator;

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

/**
 * A single fee calculator for all CLPR transaction types. Each instance is parameterized
 * by the {@link HederaFunctionality} and {@link TransactionBody.DataOneOfType} it handles.
 *
 * <p>Besides its base fee, a {@code ClprCompleteChannel} is charged {@link Extra#STATE_BYTES} for the proof bytes
 * whose verified result it writes to state, and a {@code ClprSubmitBundle} is charged {@link Extra#PROCESSING_BYTES}
 * for its bundle payload. Both are charged {@link Extra#GAS} for their verifier call, at {@link #VERIFIER_FEE_GAS}
 * since its actual gas is only known after the transaction is handled. Each extra applies only when the fee schedule
 * lists it for the transaction.
 *
 * @param functionality the CLPR functionality for fee schedule lookup
 * @param transactionType the transaction body discriminant this calculator handles
 */
public record ClprFeeCalculator(
        @NonNull HederaFunctionality functionality, @NonNull TransactionBody.DataOneOfType transactionType)
        implements ServiceFeeCalculator {
    /**
     * The verifier gas charged for an operation that verifies a proof, which is the most a verifier call is
     * priced at.
     */
    public static final long VERIFIER_FEE_GAS = 300_000L;

    @Override
    public void accumulateServiceFee(
            @NonNull final TransactionBody txnBody,
            @NonNull final SimpleFeeContext simpleFeeContext,
            @NonNull final FeeResult feeResult,
            @NonNull final FeeSchedule feeSchedule) {
        final ServiceFeeDefinition serviceDef = lookupServiceFee(feeSchedule, functionality);
        if (serviceDef == null) {
            return;
        }
        feeResult.setServiceBaseFeeTinycents(serviceDef.baseFee());
        switch (functionality) {
            case CLPR_COMPLETE_CHANNEL -> {
                final var op = txnBody.clprCompleteChannelOrThrow();
                final long proofBytes = op.configProofBytes().length()
                        + op.endpointManifestProofBytes().length();
                addScheduledExtraFee(feeResult, serviceDef, Extra.STATE_BYTES, feeSchedule, proofBytes);
                addScheduledExtraFee(feeResult, serviceDef, Extra.GAS, feeSchedule, VERIFIER_FEE_GAS);
            }
            case CLPR_SUBMIT_BUNDLE -> {
                final long bundleBytes =
                        txnBody.clprSubmitBundleOrThrow().bundlePayload().length();
                addScheduledExtraFee(feeResult, serviceDef, Extra.PROCESSING_BYTES, feeSchedule, bundleBytes);
                addScheduledExtraFee(feeResult, serviceDef, Extra.GAS, feeSchedule, VERIFIER_FEE_GAS);
            }
            default -> {
                // Only the base fee
            }
        }
    }

    @Override
    public TransactionBody.DataOneOfType getTransactionType() {
        return transactionType;
    }

    private void addScheduledExtraFee(
            @NonNull final FeeResult feeResult,
            @NonNull final ServiceFeeDefinition serviceDef,
            @NonNull final Extra extra,
            @NonNull final FeeSchedule feeSchedule,
            final long amount) {
        if (serviceDef.extras().stream().anyMatch(ref -> ref.name() == extra)) {
            addExtraFee(feeResult, serviceDef, extra, feeSchedule, amount);
        }
    }
}
