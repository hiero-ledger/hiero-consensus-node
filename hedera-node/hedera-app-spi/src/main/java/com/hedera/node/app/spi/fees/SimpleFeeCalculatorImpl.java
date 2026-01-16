// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fees;

import static org.hiero.hapi.support.fees.Extra.SIGNATURES;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.TransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;
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
    private final Map<Query.QueryOneOfType, QueryFeeCalculator> queryFeeCalculators;

    public SimpleFeeCalculatorImpl(FeeSchedule feeSchedule, Set<ServiceFeeCalculator> serviceFeeCalculators) {
        this(feeSchedule, serviceFeeCalculators, Set.of());
    }

    public SimpleFeeCalculatorImpl(
            FeeSchedule feeSchedule,
            Set<ServiceFeeCalculator> serviceFeeCalculators,
            Set<QueryFeeCalculator> queryFeeCalculators) {
        this.feeSchedule = feeSchedule;
        this.serviceFeeCalculators = serviceFeeCalculators.stream()
                .collect(Collectors.toMap(ServiceFeeCalculator::getTransactionType, Function.identity()));
        this.queryFeeCalculators = queryFeeCalculators.stream()
                .collect(Collectors.toMap(QueryFeeCalculator::getQueryType, Function.identity()));
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
            final long overage = Math.max(0, used - ref.includedCount());
            final long unitFee = getExtraFee(ref.name());
            if (used > 0) {
                result.addNodeExtra(ref.name().name(), unitFee, used, ref.includedCount(), overage);
            }
        }
    }

    /**
     * Calculates fees for CryptoDelete transactions per HIP-1261.
     * CryptoDelete uses only SIGNATURES extra for the service fee.
     *
     * @param txnBody    the transaction body
     * @param context the fee context containing signature count
     * @return the calculated fee result
     */
    @NonNull
    @Override
    public FeeResult calculateTxFee(@NonNull final TransactionBody txnBody, @NonNull final SimpleFeeContext context) {
        // Extract primitive counts (no allocations)
        final long signatures = context.numTxnSignatures();
        final var result = new FeeResult();

        // Add node base and extras
        result.addNodeBaseTC(feeSchedule.node().baseFee());
        addNodeExtras(result, feeSchedule.node().extras(), signatures);
        // Add network fee
        final int multiplier = feeSchedule.network().multiplier();
        result.setNetworkMultiplier(multiplier);

        final var serviceFeeCalculator =
                serviceFeeCalculators.get(txnBody.data().kind());
        serviceFeeCalculator.accumulateServiceFee(txnBody, context, result, feeSchedule);
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
     * @param query        The query to calculate fees for
     * @param context the query context
     * @return Never returns normally
     * @throws UnsupportedOperationException always
     */
    @Override
    public FeeResult calculateQueryFee(@NonNull final Query query, @NonNull final SimpleFeeContext context) {
        final var result = new FeeResult();
        final var queryFeeCalculator = queryFeeCalculators.get(query.query().kind());
        queryFeeCalculator.accumulateNodePayment(query, result, feeSchedule);
        return result;
    }
}
