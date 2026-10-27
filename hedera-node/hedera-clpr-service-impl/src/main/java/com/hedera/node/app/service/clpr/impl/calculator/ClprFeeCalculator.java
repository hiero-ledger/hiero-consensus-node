// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.calculator;

import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeContext;
import com.hedera.node.config.data.ClprConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

/**
 * A single fee calculator for all CLPR transaction types. Each instance is parameterized
 * by the {@link HederaFunctionality} and {@link TransactionBody.DataOneOfType} it handles.
 *
 * <p>The two transactions that dispatch to a Channel's verifier contract, {@code ClprCompleteChannel} and
 * {@code ClprSubmitBundle}, are also charged for that call at the verifier gas limit ({@link Extra#GAS}). A
 * {@code ClprCompleteChannel} is charged for the proof bytes it may write into state ({@link Extra#STATE_BYTES}), and a
 * {@code ClprSubmitBundle} for its bundle bytes ({@link Extra#CLPR_BUNDLE_BYTES}). Each extra applies only once the fee
 * schedule lists it for the transaction. A bundle's fee is what its submitter recovers from the bundle's Connectors at
 * settlement.
 *
 * @param functionality the CLPR functionality for fee schedule lookup
 * @param transactionType the transaction body discriminant this calculator handles
 */
public record ClprFeeCalculator(
        @NonNull HederaFunctionality functionality, @NonNull TransactionBody.DataOneOfType transactionType)
        implements ServiceFeeCalculator {
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
        final boolean isSubmitBundle = functionality == HederaFunctionality.CLPR_SUBMIT_BUNDLE;
        final boolean isCompleteChannel = functionality == HederaFunctionality.CLPR_COMPLETE_CHANNEL;
        if (!isSubmitBundle && !isCompleteChannel) {
            return;
        }
        if (isSubmitBundle && isScheduled(serviceDef, Extra.CLPR_BUNDLE_BYTES)) {
            final var bundleBytes =
                    txnBody.clprSubmitBundleOrThrow().bundlePayload().length();
            addExtraFee(feeResult, serviceDef, Extra.CLPR_BUNDLE_BYTES, feeSchedule, bundleBytes);
        }
        if (isCompleteChannel && isScheduled(serviceDef, Extra.STATE_BYTES)) {
            final var op = txnBody.clprCompleteChannelOrThrow();
            final var proofBytes = op.configProofBytes().length()
                    + op.endpointManifestProofBytes().length();
            addExtraFee(feeResult, serviceDef, Extra.STATE_BYTES, feeSchedule, proofBytes);
        }
        // The verifier gas limit is configuration, which is unavailable when computing fees without state
        if (isScheduled(serviceDef, Extra.GAS) && simpleFeeContext.feeContext() != null) {
            final var verifierGasLimit = simpleFeeContext
                    .configuration()
                    .getConfigData(ClprConfig.class)
                    .verifierGasLimit();
            addExtraFee(feeResult, serviceDef, Extra.GAS, feeSchedule, verifierGasLimit);
        }
    }

    @Override
    public TransactionBody.DataOneOfType getTransactionType() {
        return transactionType;
    }

    private static boolean isScheduled(@NonNull final ServiceFeeDefinition serviceDef, @NonNull final Extra extra) {
        return serviceDef.extras().stream().anyMatch(ref -> ref.name() == extra);
    }
}
