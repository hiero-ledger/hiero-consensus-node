// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.calculator;

import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_COMPLETE_CHANNEL;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_SUBMIT_BUNDLE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hedera.hapi.node.base.SubType.DEFAULT;
import static com.hedera.node.app.service.clpr.ClprServiceConstants.CLPR_STATE_BYTE_FEE_TINYCENTS;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static org.hiero.hapi.fees.FeeScheduleUtils.lookupExtraFee;
import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeContext;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.config.data.FeesConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

/**
 * A single fee calculator for all CLPR transaction types. Each instance is parameterized
 * by the {@link HederaFunctionality} and {@link TransactionBody.DataOneOfType} it handles.
 *
 * <p>Both channel completion and bundle submission reserve {@link #VERIFIER_FEE_GAS} for the verifier.
 * Bundle submission also pays {@link Extra#PROCESSING_BYTES} for the bundle payload. Channel completion's
 * {@link Extra#STATE_BYTES} charge is settled during handle, once the size of the channel state is known.
 * Each extra applies only when the fee schedule lists it for the transaction.
 *
 * @param functionality the CLPR functionality for fee schedule lookup
 * @param transactionType the transaction body discriminant this calculator handles
 */
public record ClprFeeCalculator(
        @NonNull HederaFunctionality functionality, @NonNull TransactionBody.DataOneOfType transactionType)
        implements ServiceFeeCalculator {
    /**
     * The verifier gas reserved before handle and the cap on the verifier's final charge. Actual execution
     * remains bounded by {@code clpr.verifierGasLimit}.
     */
    public static final long VERIFIER_FEE_GAS = 300_000L;

    /** CLPR bundle processing surcharge per byte ($0.0000001), in tinycents. */
    private static final long BUNDLE_BYTE_FEE_TINYCENTS = 1_000L;

    @Override
    public void accumulateServiceFee(
            @NonNull final TransactionBody txnBody,
            @NonNull final SimpleFeeContext simpleFeeContext,
            @NonNull final FeeResult feeResult,
            @NonNull final FeeSchedule feeSchedule) {
        final var serviceDef = lookupServiceFee(feeSchedule, functionality);
        if (serviceDef != null) {
            addServiceFee(feeResult, serviceDef, feeSchedule, txnBody, VERIFIER_FEE_GAS, 0);
        }
    }

    @Override
    public TransactionBody.DataOneOfType getTransactionType() {
        return transactionType;
    }

    /** Settles measured verification gas and persistent bytes before storing the channel. */
    public static void settleCompleteChannelFee(
            @NonNull final HandleContext context, final long gasUsed, final int stateBytes) {
        settleServiceFee(context, CLPR_COMPLETE_CHANNEL, gasUsed, stateBytes);
    }

    /** Settles the bundle's reserved verifier gas. Its processing bytes were known up front. */
    public static void settleBundleVerifierFee(@NonNull final HandleContext context, final long gasUsed) {
        if (gasUsed < VERIFIER_FEE_GAS) {
            settleServiceFee(context, CLPR_SUBMIT_BUNDLE, gasUsed, 0);
        }
    }

    private static void settleServiceFee(
            @NonNull final HandleContext context,
            @NonNull final HederaFunctionality function,
            final long gasUsed,
            final int stateBytes) {
        final var feeSchedule = context.simpleFeesSchedule();
        final var serviceDef = lookupServiceFee(feeSchedule, function);
        if (serviceDef == null
                || serviceDef.free()
                || serviceDef.extras().isEmpty()
                || context.configuration().getConfigData(FeesConfig.class).simpleFeesAreFree()) {
            return;
        }
        final var feeContext = (FeeContext) context;
        final var authorizer = feeContext.authorizer();
        final var payer = context.payer();
        final var body = context.body();
        if (authorizer != null && authorizer.hasWaivedFees(payer, function, body)) {
            return;
        }

        final var prepaid = new FeeResult();
        addServiceFee(prepaid, serviceDef, feeSchedule, body, VERIFIER_FEE_GAS, 0);
        final var measured = new FeeResult();
        addServiceFee(measured, serviceDef, feeSchedule, body, Math.clamp(gasUsed, 0, VERIFIER_FEE_GAS), stateBytes);
        if (prepaid.getServiceTotalTinycents() == measured.getServiceTotalTinycents()) {
            return;
        }
        final long multiplier = context.resourcePriceCalculator()
                .resourcePricesFor(function, DEFAULT)
                .congestionMultiplier();
        prepaid.applyMultiplier(multiplier, 1);
        measured.applyMultiplier(multiplier, 1);
        final long delta = feeContext.tinybarsFromTinycents(measured.getServiceTotalTinycents())
                - feeContext.tinybarsFromTinycents(prepaid.getServiceTotalTinycents());

        if (delta > 0) {
            final var builder = context.savepointStack().getBaseBuilder(StreamBuilder.class);
            if (builder.isUserDispatch()) {
                validateTrue(delta <= body.transactionFee() - builder.transactionFee(), INSUFFICIENT_TX_FEE);
            }
            validateTrue(context.tryToChargePayer(delta), INSUFFICIENT_PAYER_BALANCE);
        } else if (delta < 0) {
            context.refundServiceFee(payer, -delta);
        }
    }

    private static void addServiceFee(
            @NonNull final FeeResult result,
            @NonNull final ServiceFeeDefinition serviceDef,
            @NonNull final FeeSchedule schedule,
            @NonNull final TransactionBody body,
            final long verifierGas,
            final int stateBytes) {
        result.setServiceBaseFeeTinycents(serviceDef.baseFee());
        if (serviceDef.name() == CLPR_COMPLETE_CHANNEL || serviceDef.name() == CLPR_SUBMIT_BUNDLE) {
            for (final var extra : serviceDef.extras()) {
                switch (extra.name()) {
                    case GAS ->
                        result.addServiceExtraFeeTinycents(
                                Extra.GAS.name(),
                                lookupExtraFee(schedule, Extra.GAS).fee(),
                                verifierGas,
                                extra.includedCount());
                    case STATE_BYTES -> {
                        if (serviceDef.name() == CLPR_COMPLETE_CHANNEL) {
                            result.addServiceExtraFeeTinycents(
                                    Extra.STATE_BYTES.name(),
                                    CLPR_STATE_BYTE_FEE_TINYCENTS,
                                    stateBytes,
                                    extra.includedCount());
                        }
                    }
                    case PROCESSING_BYTES -> {
                        if (serviceDef.name() == CLPR_SUBMIT_BUNDLE) {
                            final long bundleBytes = body.clprSubmitBundleOrThrow()
                                    .bundlePayload()
                                    .length();
                            result.addServiceExtraFeeTinycents(
                                    Extra.PROCESSING_BYTES.name(),
                                    BUNDLE_BYTE_FEE_TINYCENTS,
                                    bundleBytes,
                                    extra.includedCount());
                        }
                    }
                    default -> {
                        // Other extras do not apply to either operation.
                    }
                }
            }
        }
    }
}
