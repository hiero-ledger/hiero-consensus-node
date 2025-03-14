// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.gas;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.FEE_SCHEDULE_UNITS_PER_TINYCENT;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.transaction.TransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.ToLongBiFunction;

/**
 * Computes the gas requirements for dispatching transactions or queries.
 */
public class SystemContractGasCalculator {
    private static final long FIXED_VIEW_GAS_COST = 100L;

    // This represents the predefined gas price which is $0.000_000_0852 per unit of gas.
    // 852_000 = $0.000_000_0852 * 100(cents per dollars) * 100_000_000 (tiny cents per cents) * 1000 (Fee schedule
    // units
    // per tiny cents). For more info -> https://hedera.com/blog/rolling-smart-contract-hedera-api-fees-into-gas-fees
    private static final long FIXED_TINY_CENT_GAS_PRICE_COST = 852_000L;

    private final TinybarValues tinybarValues;
    private final CanonicalDispatchPrices dispatchPrices;
    private final ToLongBiFunction<TransactionBody, AccountID> feeCalculator;

    public SystemContractGasCalculator(
            @NonNull final TinybarValues tinybarValues,
            @NonNull final CanonicalDispatchPrices dispatchPrices,
            @NonNull final ToLongBiFunction<TransactionBody, AccountID> feeCalculator) {
        this.tinybarValues = requireNonNull(tinybarValues);
        this.dispatchPrices = requireNonNull(dispatchPrices);
        this.feeCalculator = requireNonNull(feeCalculator);
    }

    /**
     * Convenience method that, given a transaction body whose cost can be directly compared to the minimum
     * cost of a dispatch type, returns the gas requirement for the transaction to be dispatched.
     *
     * @param body the transaction body to be dispatched
     * @param dispatchType the type of dispatch whose minimum cost should be respected
     * @param payer the payer of the transaction
     * @return the gas requirement for the transaction to be dispatched
     */
    public long gasRequirement(
            @NonNull final TransactionBody body,
            @NonNull final DispatchType dispatchType,
            @NonNull final AccountID payer) {
        requireNonNull(body);
        requireNonNull(dispatchType);
        requireNonNull(payer);
        return gasRequirementWithTinycents(body, payer, dispatchPrices.canonicalPriceInTinycents(dispatchType));
    }

    /**
     * Compares the canonical price and the feeCalculator's calculated price and uses the maximum of the two to
     * calculate the gas requirement and returns it.
     * @param body the transaction body
     * @param payer the payer of the transaction
     * @param minimumPriceInTinycents the minimum price in tiny cents
     * @return the gas requirement for the transaction
     */
    public long gasRequirementWithTinycents(
            @NonNull final TransactionBody body, @NonNull final AccountID payer, final long minimumPriceInTinycents) {
        final var computedPriceInTinybars = feeCalculator.applyAsLong(body, payer);
        final var priceInTinycents =
                Math.max(minimumPriceInTinycents, tinybarValues.asTinycents(computedPriceInTinybars));
        // For the rare cases where computedPrice > minimumPriceInTinycents:
        // Precision loss may occur as we convert between tinyBars and tinycents, but it is typically negligible.
        // The minimal computed price is > 1e6 tinycents, ensuring enough precision.
        // In most cases, the gas difference is zero.
        // In scenarios where we compare significant price fluctuations (200x, 100x), the gas difference should still be
        // unlikely to exceed 0 gas.
        return gasRequirementFromTinycents(priceInTinycents, tinybarValues.childTransactionTinycentGasPrice());
    }

    /**
     * Returns the gas price for the top-level HAPI operation.
     *
     * @return the gas price for the top-level HAPI operation in tinyBars.
     */
    public long topLevelGasPriceInTinyBars() {
        return tinybarValues.topLevelTinybarGasPriceFullPrecision();
    }

    /**
     * Estimates the gas requirement for a view operation.
     * The minimum gas requirement is 100 gas.
     * For all view operations, the gas requirement is determined using the canonical gas value
     * for the TOKEN_INFO dispatch type, as specified in the canonical-prices.json.
     * The TOKEN_INFO operation is representative of view operations.
     *
     * @return the gas requirement for a view operation
     */
    public long viewGasRequirement() {
        final var gasRequirement = gasRequirementFromTinycents(
                dispatchPrices.canonicalPriceInTinycents(DispatchType.TOKEN_INFO), FIXED_TINY_CENT_GAS_PRICE_COST);
        return Math.max(FIXED_VIEW_GAS_COST, gasRequirement);
    }

    /**
     * Given a dispatch type, returns the canonical gas requirement for that dispatch type.
     * Useful when providing a ballpark gas requirement in the absence of a valid
     * transaction body for the dispatch type.
     * Used for non-query operations.
     *
     * @param dispatchType the dispatch type
     * @return the canonical gas requirement for that dispatch type
     */
    public long canonicalGasRequirement(@NonNull final DispatchType dispatchType) {
        return gasRequirementFromTinycents(
                dispatchPrices.canonicalPriceInTinycents(dispatchType),
                tinybarValues.childTransactionTinycentGasPrice());
    }

    /**
     * Given a dispatch type, returns the canonical price for that dispatch type.
     *
     * @param dispatchType the dispatch type
     * @return the canonical price for that dispatch type
     */
    public long canonicalPriceInTinycents(@NonNull final DispatchType dispatchType) {
        requireNonNull(dispatchType);
        return dispatchPrices.canonicalPriceInTinycents(dispatchType);
    }

    /**
     * Given a dispatch, returns the canonical price for that dispatch.
     *
     * @param body the transaction body to be dispatched
     * @param payer the payer account
     * @return the canonical price for that dispatch
     */
    public long feeCalculatorPriceInTinyBars(@NonNull final TransactionBody body, @NonNull final AccountID payer) {
        return feeCalculator.applyAsLong(body, payer);
    }

    /**
     * Given a gas requirement, returns the equivalent tinybar cost at the current gas price.
     *
     * @param gas the gas requirement
     * @return the equivalent tinybar cost at the current gas price
     */
    public long gasCostInTinybars(final long gas) {
        return gas * tinybarValues.childTransactionTinybarGasPrice();
    }

    /**
     * Calculates the gas requirement for an operation based on the provided tinycents price and gas price.
     * The calculation rounds up the result to the nearest gas unit and then adds 20% to the computed gas
     * requirement to account for the premium of executing a HAPI operation within the EVM.
     *
     * @param tinycentsPrice the price of the operation in tinycents
     * @param gasPriceInCents the current gas price in cents
     * @return the computed gas requirement for the operation
     */
    private long gasRequirementFromTinycents(long tinycentsPrice, final long gasPriceInCents) {
        final var gasRequirement =
                (tinycentsPrice + gasPriceInCents - 1) * FEE_SCHEDULE_UNITS_PER_TINYCENT / gasPriceInCents;
        return gasRequirement + (gasRequirement / 5);
    }
}
