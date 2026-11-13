// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CONSENSUS_GAS_EXHAUSTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.THROTTLED_AT_CONSENSUS;
import static com.hedera.hapi.node.base.SubType.DEFAULT;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.Type.CLPR_GAS_ACCOUNTING;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.node.config.data.ContractsConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigInteger;

/** Pays network gas fees independently of the synthetic EVM sender and bounds a CLPR transaction's total gas. */
public final class ClprGasAccounting {
    private final HandleContext context;
    private final AccountID parentPayer;
    private final long totalGas;
    private long spentFee;
    private long reservedFee;
    private boolean prepaid;
    private boolean refunded;
    private long remainingGas;
    private boolean verifierAccounted;
    private boolean verifierAllowanceReserved;
    private Long gasPrice;

    private ClprGasAccounting(@NonNull final HandleContext context) {
        this.context = context;
        parentPayer = context.payer();
        final long maximum = Math.max(
                0, context.configuration().getConfigData(ContractsConfig.class).maxGasPerTransaction());
        final var clprConfig = context.configuration().getConfigData(ClprConfig.class);
        final var body = context.body();
        if (body != null && body.hasClprCompleteChannel()) {
            totalGas = Math.min(maximum, Math.max(0, clprConfig.verifierGasLimit()));
        } else if (body != null && body.hasClprSubmitBundle()) {
            // A bundle runs many separately capped calls, so its total can exceed one transaction's gas
            totalGas = Math.max(0, clprConfig.submitBundleGasLimit());
        } else {
            totalGas = maximum;
        }
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

    /** Prepays the whole transaction allowance from the original payer before any EVM work. */
    public boolean reserveParentGas() {
        if (prepaid) return true;
        final long fee = costForGas(totalGas);
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
        final boolean prepaid = prepay(payer, gasLimit);
        if (prepaid) {
            verifierAccounted = true;
        }
        return prepaid;
    }

    /** Refunds unused gas; only known pre-execution throttles are free when no contract result exists. */
    public long settle(@NonNull final AccountID payer, final long gasLimit, @Nullable final StreamBuilder result) {
        final long gasUsed;
        if (result != null && result.hasContractResult()) {
            gasUsed = Math.clamp(result.getGasUsedForContractTxn(), 0, gasLimit);
        } else if (result != null
                && (result.status() == THROTTLED_AT_CONSENSUS || result.status() == CONSENSUS_GAS_EXHAUSTED)) {
            gasUsed = 0;
        } else {
            // An unknown missing result still consumes the full reservation to prevent unbilled work.
            gasUsed = gasLimit;
        }
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
