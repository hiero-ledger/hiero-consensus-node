// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fees;

import static org.hiero.hapi.fees.FeeScheduleUtils.lookupExtraFee;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.TransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.ExtraFeeReference;
import org.hiero.hapi.support.fees.FeeSchedule;

/**
 * Base class for simple fee calculators. Provides reusable utility methods for common fee
 * calculation patterns per HIP-1261.
 *
 * <p>Subclasses implement {@link SimpleFeeCalculator} directly and can use the static utility
 * methods provided here to avoid code duplication.
 */
public abstract class AbstractSimpleFeeCalculator implements SimpleFeeCalculator {

    protected final FeeSchedule feeSchedule;

    protected AbstractSimpleFeeCalculator(FeeSchedule feeSchedule) {
        this.feeSchedule = feeSchedule;
    }

    /**
     * Adds fees from a list of extras to the result, using primitive counts.
     * Avoids Map allocation for hot path performance.
     *
     * @param result the fee result to accumulate fees into
     * @param feeType "Node" or "Service" - determines which add method to call
     * @param extras the list of extra fee references from the fee schedule
     * @param signatures the number of signatures
     * @param bytes the transaction size in bytes
     * @param keys the number of keys
     */
    protected void addExtraFees(
            @NonNull final FeeResult result,
            @NonNull final String feeType,
            @NonNull final Iterable<ExtraFeeReference> extras,
            final long signatures,
            final long bytes,
            final long keys) {
        for (final ExtraFeeReference ref : extras) {
            final long used =
                    switch (ref.name()) {
                        case SIGNATURES -> signatures;
                        case BYTES -> bytes;
                        case KEYS -> keys;
                        default -> 0; // Ignore extras not applicable to this transaction
                    };

            if (used > ref.includedCount()) {
                final long overage = used - ref.includedCount();
                final long unitFee = lookupExtraFee(feeSchedule, ref).fee();
                final long cost = overage * unitFee;

                if ("Node".equals(feeType)) {
                    result.addNodeFee("Node Overage of " + ref.name().name(), overage, cost);
                } else {
                    result.addServiceFee("Overage of " + ref.name().name(), overage, cost);
                }
            }
        }
    }

    /**
     * Utility: Counts all keys including nested ones in threshold/key lists.
     * Useful for calculating KEYS extra fees per HIP-1261.
     *
     * @param key The key structure to count
     * @return The total number of simple keys (ED25519, ECDSA_SECP256K1, ECDSA_384)
     */
    protected static long countKeys(@NonNull final Key key) {
        return switch (key.key().kind()) {
            case ED25519, ECDSA_SECP256K1, ECDSA_384 -> 1L;
            case THRESHOLD_KEY ->
                key.thresholdKeyOrThrow().keys().keys().stream()
                        .mapToLong(AbstractSimpleFeeCalculator::countKeys)
                        .sum();
            case KEY_LIST ->
                key.keyListOrThrow().keys().stream()
                        .mapToLong(AbstractSimpleFeeCalculator::countKeys)
                        .sum();
            default -> 0L;
        };
    }

    /**
     * Default implementation for transaction fee calculation.
     *
     * @param calculatorState calculator state
     * @return Never returns normally
     * @throws UnsupportedOperationException always
     */
    @Override
    @NonNull
    public FeeResult calculateTxFee(@NonNull TransactionBody txnBody, @Nullable CalculatorState calculatorState) {
        throw new UnsupportedOperationException(
                "Txn fee calculation not supported for " + getClass().getSimpleName());
    }

    /**
     * Default implementation for query fee calculation.
     *
     * @param query The query to calculate fees for
     * @param calculatorState calculator state
     * @return Never returns normally
     * @throws UnsupportedOperationException always
     */
    @Override
    @NonNull
    public FeeResult calculateQueryFee(@NonNull final Query query, @Nullable final CalculatorState calculatorState) {
        throw new UnsupportedOperationException(
                "Query fee calculation not supported for " + getClass().getSimpleName());
    }
}
