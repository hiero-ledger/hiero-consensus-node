// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.hapi.node.base.ResponseCodeEnum.FAIL_INVALID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_GAS_LIMIT_EXCEEDED;
import static com.hedera.hapi.node.base.SubType.DEFAULT;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.Type.CLPR_GAS_ACCOUNTING;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.spi.workflows.ContractGasUsage;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.RollbackAwareException;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.data.FeesConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;

/** Pays network gas fees independently of the synthetic EVM sender and bounds a CLPR transaction's total gas. */
public final class ClprGasAccounting {
    /** The native verifier billing ceiling, independent of the shared execution limit. */
    public static final long MAX_BILLED_VERIFIER_GAS = 300_000L;

    private final HandleContext context;
    private final AccountID parentPayer;
    private final long totalGas;
    private long spentFee;
    private long reservedFee;
    private long reservedRollbackFee;
    private boolean prepaid;
    private boolean refunded;
    private long remainingGas;
    private boolean verifierAccounted;
    private boolean verifierAllowanceReserved;
    private long remainingVerifierBillingGas = MAX_BILLED_VERIFIER_GAS;
    private Long gasPrice;

    private ClprGasAccounting(@NonNull final HandleContext context) {
        this.context = context;
        parentPayer = context.payer();
        final long maximum = Math.max(
                0, context.configuration().getConfigData(ContractsConfig.class).maxGasPerTransaction());
        totalGas = context.body() != null && context.body().hasClprCompleteChannel()
                ? Math.min(
                        maximum,
                        Math.max(
                                0,
                                context.configuration()
                                        .getConfigData(ClprConfig.class)
                                        .verifierGasLimit()))
                : maximum;
        remainingGas = totalGas;
    }

    /** Returns the payment ledger shared by verifier and application calls in this parent dispatch. */
    public static ClprGasAccounting forContext(@NonNull final HandleContext context) {
        final var metadata = context.dispatchMetadata();
        final var existing = metadata.getMetadataIfPresent(CLPR_GAS_ACCOUNTING, ClprGasAccounting.class);
        if (existing != null) {
            return existing;
        }
        final var accounting = new ClprGasAccounting(context);
        metadata.putMetadata(CLPR_GAS_ACCOUNTING, accounting);
        return accounting;
    }

    /** Reserves signed-fee headroom for a possible rollback charge without collecting it. */
    public void reserveRollbackFee(final long maximumFee) {
        if (maximumFee < 0) {
            throw new IllegalArgumentException("Negative rollback fee reservation");
        }
        final var builder = context.savepointStack().getBaseBuilder(StreamBuilder.class);
        if (builder.isUserDispatch()) {
            final long offered = context.body().transactionFee();
            final long charged = builder.transactionFee();
            validateTrue(charged <= offered && maximumFee <= offered - charged, INSUFFICIENT_TX_FEE);
        }
        reservedRollbackFee = maximumFee;
    }

    /** Prepays the whole transaction allowance from the original payer before any EVM work. */
    public boolean reserveParentGas() {
        if (prepaid) return true;
        final long billableGas = context.body() != null && context.body().hasClprCompleteChannel()
                ? Math.min(totalGas, MAX_BILLED_VERIFIER_GAS)
                : totalGas;
        final long fee = costForGas(billableGas);
        if (fee > 0) {
            final var builder = context.savepointStack().getBaseBuilder(StreamBuilder.class);
            if (builder.isUserDispatch()) {
                // Base, byte and possible rollback fees also count against the signed maximum.
                final long offered = context.body().transactionFee();
                final long charged = builder.transactionFee();
                validateTrue(
                        charged <= offered
                                && reservedRollbackFee <= offered - charged
                                && fee <= offered - charged - reservedRollbackFee,
                        INSUFFICIENT_TX_FEE);
            }
        }
        if (fee > 0 && !context.tryToCharge(parentPayer, fee)) return false;
        reservedFee = fee;
        prepaid = true;
        return true;
    }

    /** Returns a callback grant capped by the gas left after verifier and earlier callback execution. */
    public long availableApplicationGas(final long perMessageLimit) {
        if (!verifierAccounted && !verifierAllowanceReserved) {
            // Reserve the full allowance when an older verifier adapter does not report its gas.
            remainingGas -= Math.min(
                    remainingGas,
                    Math.max(
                            0,
                            context.configuration()
                                    .getConfigData(ClprConfig.class)
                                    .verifierGasLimit()));
            verifierAllowanceReserved = true;
        }
        return Math.min(Math.max(0, perMessageLimit), remainingGas);
    }

    /** Computes ordinary ContractCall gas pricing, including congestion and the active exchange rate. */
    public long costForGas(final long gas) {
        if (gas < 0) {
            throw new IllegalArgumentException("Negative gas allowance");
        }
        if (gasPrice == null) {
            final var schedule = context.simpleFeesSchedule();
            final var contractFee = schedule == null ? null : lookupServiceFee(schedule, CONTRACT_CALL);
            if (context.configuration().getConfigData(FeesConfig.class).simpleFeesAreFree()
                    || (contractFee != null && contractFee.free())) {
                gasPrice = 0L;
                return 0;
            }
            final long tinycents = context.getGasPriceInTinycents();
            if (tinycents == 0) {
                gasPrice = 0L;
            } else {
                final var prices = context.resourcePriceCalculator().resourcePricesFor(CONTRACT_CALL, DEFAULT);
                final var rate = context.exchangeRateInfo().activeRate(context.consensusNow());
                gasPrice = BigInteger.valueOf(tinycents)
                        .multiply(BigInteger.valueOf(prices.congestionMultiplier()))
                        .multiply(BigInteger.valueOf(rate.hbarEquiv()))
                        .divide(BigInteger.valueOf(rate.centEquiv()))
                        .longValueExact();
                if (gasPrice < 0) {
                    throw new IllegalArgumentException("Negative gas price");
                }
            }
        }
        return Math.multiplyExact(gas, gasPrice);
    }

    /** Reserves gas and pays its worst-case network fee before entering application code. */
    public boolean prepay(@NonNull final AccountID payer, final long gasLimit) {
        if (gasLimit <= 0 || gasLimit > remainingGas || refunded || !reserveParentGas()) return false;
        remainingGas -= gasLimit;
        return true;
    }

    /** Prepays verifier gas; its actual use then counts toward the same transaction cap. */
    public boolean prepayVerifier(@NonNull final AccountID payer, final long gasLimit) {
        validateTrue(gasLimit > 0, INSUFFICIENT_GAS);
        validateTrue(!refunded, FAIL_INVALID);
        validateTrue(gasLimit <= remainingGas, MAX_GAS_LIMIT_EXCEEDED);
        final boolean prepaid = prepay(payer, gasLimit);
        if (prepaid) {
            verifierAccounted = true;
        }
        return prepaid;
    }

    /** Whether the shared escrow owns the verifier fee, preventing duplicate native settlement. */
    public static boolean hasVerifierAccounting(@NonNull final HandleContext context) {
        final var metadata = context.dispatchMetadata();
        final var accounting =
                metadata == null ? null : metadata.getMetadataIfPresent(CLPR_GAS_ACCOUNTING, ClprGasAccounting.class);
        return accounting != null && accounting.verifierAccounted;
    }

    /** Bills reported callback gas independently of cleared record/block stream builders. */
    public long settleWithGasUsage(
            @NonNull final AccountID payer, final long gasLimit, @NonNull final ContractGasUsage usage) {
        return settleGas(Math.clamp(usage.gasUsed(), 0, gasLimit));
    }

    /** Bills verifier execution once, sharing the native operation's 300,000-unit ceiling. */
    public long settleVerifierWithGasUsage(
            @NonNull final AccountID payer, final long gasLimit, @NonNull final ContractGasUsage usage) {
        final long billedGas = Math.min(Math.clamp(usage.gasUsed(), 0, gasLimit), remainingVerifierBillingGas);
        remainingVerifierBillingGas -= billedGas;
        return settleGas(billedGas);
    }

    private long settleGas(final long gasUsed) {
        // Consume every granted gas unit for capacity, independently of billed EVM refunds.
        final long actualFee = costForGas(gasUsed);
        spentFee = Math.addExact(spentFee, actualFee);
        return actualFee;
    }

    /** Refunds unused network escrow exactly once, after the parent has succeeded. */
    public void refundUnusedGas() {
        if (prepaid && !refunded) {
            final long refund = Math.subtractExact(reservedFee, spentFee);
            if (refund > 0) context.refundHandlerCharge(parentPayer, refund);
            refunded = true;
        }
    }

    public static void refundUnusedGas(@NonNull final HandleContext context) {
        final var metadata = context.dispatchMetadata();
        final var accounting =
                metadata == null ? null : metadata.getMetadataIfPresent(CLPR_GAS_ACCOUNTING, ClprGasAccounting.class);
        if (accounting != null) accounting.refundUnusedGas();
    }

    /** Looks up existing payments without creating a ledger when validation failed before any EVM work. */
    public static HandleException preservingChargesOnRollback(
            @NonNull final HandleContext context, @NonNull final HandleException failure) {
        final var metadata = context.dispatchMetadata();
        final var accounting =
                metadata == null ? null : metadata.getMetadataIfPresent(CLPR_GAS_ACCOUNTING, ClprGasAccounting.class);
        return accounting == null ? failure : accounting.preservingChargesOnRollback(failure);
    }

    /** Preserves the ordinary internal-error service-fee waiver and platform alert. */
    public static RuntimeException preservingChargesOnRollback(
            @NonNull final HandleContext context, @NonNull final RuntimeException failure) {
        final var metadata = context.dispatchMetadata();
        final var accounting =
                metadata == null ? null : metadata.getMetadataIfPresent(CLPR_GAS_ACCOUNTING, ClprGasAccounting.class);
        if (accounting == null || accounting.spentFee == 0 || failure instanceof RollbackAwareException) {
            return failure;
        }
        final long actualFee = accounting.spentFee;
        return new RollbackAwareException(
                failure, (fees, rollbackContext) -> rollbackContext.tryToCharge(accounting.parentPayer, actualFee));
    }

    /** Retains payment for executed gas when a later parent failure rolls back application state. */
    public HandleException preservingChargesOnRollback(@NonNull final HandleException failure) {
        if (spentFee == 0) return failure;
        final long actualFee = spentFee;
        return new HandleException(failure.getStatus(), (fees, rollbackContext) -> {
            rollbackContext.tryToCharge(parentPayer, actualFee);
            failure.maybeReplay(fees, rollbackContext);
        });
    }
}
