// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import static com.hedera.hapi.util.HapiUtils.functionOf;
import static com.hedera.node.app.hapi.utils.CommonUtils.clampedMultiply;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.util.UnknownHederaFunctionality;
import com.hedera.node.app.fees.congestion.CongestionMultipliers;
import com.hedera.node.app.spi.fees.QueryFeeCalculator;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

    private static final Logger log = LogManager.getLogger(SimpleFeeCalculatorImpl.class);

    protected final FeeSchedule feeSchedule;
    private final Map<TransactionBody.DataOneOfType, ServiceFeeCalculator> serviceFeeCalculators;
    private final Map<Query.QueryOneOfType, QueryFeeCalculator> queryFeeCalculators;
    private final CongestionMultipliers congestionMultipliers;

    public SimpleFeeCalculatorImpl(
            @NonNull FeeSchedule feeSchedule,
            @NonNull Set<ServiceFeeCalculator> serviceFeeCalculators,
            @NonNull Set<QueryFeeCalculator> queryFeeCalculators,
            @NonNull CongestionMultipliers congestionMultipliers) {
        this.feeSchedule = feeSchedule;
        this.serviceFeeCalculators = serviceFeeCalculators.stream()
                .collect(Collectors.toMap(ServiceFeeCalculator::getTransactionType, Function.identity()));
        this.queryFeeCalculators = queryFeeCalculators.stream()
                .collect(Collectors.toMap(QueryFeeCalculator::getQueryType, Function.identity()));
        this.congestionMultipliers = congestionMultipliers;
    }

    @VisibleForTesting
    public SimpleFeeCalculatorImpl(
            @NonNull FeeSchedule feeSchedule,
            @NonNull Set<ServiceFeeCalculator> serviceFeeCalculators,
            @NonNull Set<QueryFeeCalculator> queryFeeCalculators) {
        this(feeSchedule, serviceFeeCalculators, queryFeeCalculators, null);
    }

    @VisibleForTesting
    public SimpleFeeCalculatorImpl(
            @NonNull FeeSchedule feeSchedule, @NonNull Set<ServiceFeeCalculator> serviceFeeCalculators) {
        this(feeSchedule, serviceFeeCalculators, Set.of());
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
            @NonNull final FeeResult result,
            @NonNull final Iterable<ExtraFeeReference> extras,
            final long signatures,
            final long bytes) {
        for (final ExtraFeeReference ref : extras) {
            final long used =
                    switch (ref.name()) {
                        case SIGNATURES -> signatures;
                        case PROCESSING_BYTES -> bytes;
                        default -> 0;
                    };
            final long unitFee = getExtraFee(ref.name());
            result.addNodeExtraFeeTinycents(ref.name().name(), unitFee, used, ref.includedCount());
        }
    }

    /**
     * Calculates fees for transactions per HIP-1261.
     * Node fee includes BYTES (full transaction size) and SIGNATURES extras.
     * Service fee is transaction-specific.
     * If congestion multipliers are configured and a store factory is available,
     * the congestion multiplier will be applied to the total fee.
     *
     * @param txnBody the transaction body
     * @param simpleFeeContext the fee context containing signature count and full transaction bytes
     * @return the calculated fee result
     */
    @NonNull
    @Override
    public FeeResult calculateTxFee(
            @NonNull final TransactionBody txnBody, @NonNull final SimpleFeeContext simpleFeeContext) {
        // Extract primitive counts (no allocations)
        final long signatures = simpleFeeContext.numTxnSignatures();
        // Get full transaction size in bytes (includes body, signatures, and all transaction data)
        final long bytes = simpleFeeContext.numTxnBytes();
        final var result = new FeeResult();
        // Add node base and extras (bytes and payer signatures)
        result.setNodeBaseFeeTinycents(feeSchedule.node().baseFee());
        addNodeExtras(result, feeSchedule.node().extras(), signatures, bytes);
        // Add network fee
        final int multiplier = feeSchedule.network().multiplier();
        result.setNetworkMultiplier(multiplier);

        final var serviceFeeCalculator =
                serviceFeeCalculators.get(txnBody.data().kind());
        serviceFeeCalculator.accumulateServiceFee(txnBody, simpleFeeContext, result, feeSchedule);

        // Apply congestion multiplier if available
        return applyCongestionMultiplier(txnBody, simpleFeeContext, result);
    }

    /**
     * Applies the congestion multiplier to the fee result.
     * Gets the ReadableStoreFactory from the FeeContext implementation.
     *
     * @param txnBody the transaction body
     * @param simpleFeeContext the simple fee context
     * @param result the base fee result
     * @return a new FeeResult with congestion multiplier applied, or the original if no multiplier
     */
    private FeeResult applyCongestionMultiplier(
            @NonNull final TransactionBody txnBody,
            @Nullable final SimpleFeeContext simpleFeeContext,
            @NonNull final FeeResult result) {
        if (simpleFeeContext == null || simpleFeeContext.feeContext() == null || congestionMultipliers == null) {
            return result;
        }

        try {
            final HederaFunctionality functionality = functionOf(txnBody);
            final var feeContext = simpleFeeContext.feeContext();
            final long congestionMultiplier = congestionMultipliers.maxCurrentMultiplier(
                    txnBody, functionality, feeContext.readableStoreFactory());
            if (congestionMultiplier <= 1) {
                return result;
            }
            return new FeeResult(
                    clampedMultiply(result.getServiceTotalTinycents(), congestionMultiplier),
                    clampedMultiply(result.getNodeTotalTinycents(), congestionMultiplier),
                    result.getNetworkMultiplier());
        } catch (UnknownHederaFunctionality e) {
            log.error("Unknown Hedera functionality for transaction body: {}", txnBody, e);
            return result;
        }
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
     * Default implementation for query fee calculation.
     *
     * @param query The query to calculate fees for
     * @param simpleFeeContext the query context
     * @return Never returns normally
     * @throws UnsupportedOperationException always
     */
    @Override
    public FeeResult calculateQueryFee(@NonNull final Query query, @NonNull final SimpleFeeContext simpleFeeContext) {
        final var result = new FeeResult();
        final var queryFeeCalculator = queryFeeCalculators.get(query.query().kind());
        queryFeeCalculator.accumulateNodePayment(query, simpleFeeContext, result, feeSchedule);
        return result;
    }
}
