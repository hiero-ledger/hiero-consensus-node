// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fees;

import static org.hiero.hapi.support.fees.Extra.SIGNATURES;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.TransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.ExtraFeeReference;
import org.hiero.hapi.support.fees.FeeSchedule;

/**
 * Base class for simple fee calculators. Provides reusable utility methods for common fee
 * calculation patterns per HIP-1261.
 *
 * <p>Subclasses implement {@link SimpleFeeCalculator} directly and can use the static utility
 * methods provided here to avoid code duplication.
 */
public class SimpleFeeCalculatorImpl implements SimpleFeeCalculator {

    protected final FeeSchedule feeSchedule;
    private final Map<TransactionBody.DataOneOfType, ServiceFeeCalculator> serviceFeeCalculators;

    public SimpleFeeCalculatorImpl(FeeSchedule feeSchedule, Set<ServiceFeeCalculator> serviceFeeCalculators) {
        this.feeSchedule = feeSchedule;
        this.serviceFeeCalculators = serviceFeeCalculators.stream()
                .collect(Collectors.toMap(ServiceFeeCalculator::getTransactionType, Function.identity()));
    }

    /**
     * Adds fees from a list of extras to the result, using primitive counts.
     * Avoids Map allocation for hot path performance.
     *
     * @param result the fee result to accumulate fees into
     * @param extras the list of extra fee references from the fee schedule
     * @param signatures the number of signatures
     */
    private void addNodeExtras(
            @NonNull final FeeResult result, @NonNull final Iterable<ExtraFeeReference> extras, final long signatures) {
        for (final ExtraFeeReference ref : extras) {
            final long used = ref.name() == SIGNATURES ? signatures : 0;
            if (used > ref.includedCount()) {
                final long overage = used - ref.includedCount();
                final long unitFee = getExtraFee(ref.name());

                result.addNodeFee(overage, unitFee);
            }
        }
    }

    /**
     * Calculates fees for CryptoDelete transactions per HIP-1261.
     * CryptoDelete uses only SIGNATURES extra for the service fee.
     *
     * @param txnBody the transaction body
     * @param feeContext the fee context containing signature count
     * @return the calculated fee result
     */
    @NonNull
    @Override
    public FeeResult calculateTxFee(@NonNull final TransactionBody txnBody, @Nullable final FeeContext feeContext) {
        // Extract primitive counts (no allocations)
        final long signatures = feeContext != null ? feeContext.numTxnSignatures() : 0;
        final var result = new FeeResult();

        // Add node base and extras
        result.addNodeFee(1, feeSchedule.node().baseFee());
        addNodeExtras(result, feeSchedule.node().extras(), signatures);
        // Add network fee
        final int multiplier = feeSchedule.network().multiplier();
        result.addNetworkFee(result.node * multiplier);

        final var serviceFeeCalculator =
                serviceFeeCalculators.get(txnBody.data().kind());
        serviceFeeCalculator.accumulateServiceFee(txnBody, feeContext, result, feeSchedule);
        return result;
    }

    @Override
    public long getExtraFee(Extra extra) {
        return feeSchedule.extras().stream()
                .filter(feeDefinition -> feeDefinition.name() == extra)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Extra fee not found: " + extra))
                .fee();
    }

    /**
     * Utility: Counts all keys including nested ones in threshold/key lists.
     * Useful for calculating KEYS extra fees per HIP-1261.
     *
     * @param key The key structure to count
     * @return The total number of simple keys (ED25519, ECDSA_SECP256K1, ECDSA_384)
     */
    public static long countKeys(@NonNull final Key key) {
        return switch (key.key().kind()) {
            case ED25519, ECDSA_SECP256K1, ECDSA_384 -> 1L;
            case THRESHOLD_KEY ->
                key.thresholdKeyOrThrow().keys().keys().stream()
                        .mapToLong(SimpleFeeCalculatorImpl::countKeys)
                        .sum();
            case KEY_LIST ->
                key.keyListOrThrow().keys().stream()
                        .mapToLong(SimpleFeeCalculatorImpl::countKeys)
                        .sum();
            default -> 0L;
        };
    }

    /**
     * Default implementation for query fee calculation.
     *
     * @param query The query to calculate fees for
     * @param feeContext fee context
     * @return Never returns normally
     * @throws UnsupportedOperationException always
     */
    @Override
    @NonNull
    public FeeResult calculateQueryFee(@NonNull final Query query, @Nullable final FeeContext feeContext) {
        throw new UnsupportedOperationException(
                "Query fee calculation not supported for " + getClass().getSimpleName());
    }
}
