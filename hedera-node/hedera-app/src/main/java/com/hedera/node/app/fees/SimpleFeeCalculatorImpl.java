// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import static com.hedera.node.app.workflows.handle.HandleWorkflow.ALERT_MESSAGE;
import static java.util.Objects.requireNonNull;
import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;
import static org.hiero.hapi.fees.HighVolumePricingCalculator.DEFAULT_HIGH_VOLUME_MULTIPLIER;
import static org.hiero.hapi.fees.HighVolumePricingCalculator.HIGH_VOLUME_MULTIPLIER_SCALE;
import static org.hiero.hapi.fees.HighVolumePricingCalculator.HIGH_VOLUME_PRICING_FUNCTIONS;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.fees.congestion.CongestionMultipliers;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.QueryFeeCalculator;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.fees.HighVolumePricingCalculator;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.ExtraFeeReference;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

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
        this.feeSchedule = requireNonNull(feeSchedule);
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
     * For high-volume transactions (HIP-1313), applies a dynamic multiplier based on throttle utilization.
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
        result.setNodeBaseFeeTinycents(requireNonNull(feeSchedule.node()).baseFee());
        addNodeExtras(result, feeSchedule.node().extras(), signatures, bytes);
        // Add network fee
        final int multiplier = requireNonNull(feeSchedule.network()).multiplier();
        result.setNetworkMultiplier(multiplier);

        final var serviceFeeCalculator =
                serviceFeeCalculators.get(txnBody.data().kind());
        if(serviceFeeCalculator == null) {
            System.out.println("missing service fee calculator for kind " + txnBody.data().kind());
        }
        serviceFeeCalculator.accumulateServiceFee(txnBody, simpleFeeContext, result, feeSchedule);

        final var functionality = simpleFeeContext.functionality();
        final var isHighVolumeFunction = HIGH_VOLUME_PRICING_FUNCTIONS.contains(functionality);

        // Apply high-volume pricing multiplier if applicable (HIP-1313)
        if (txnBody.highVolume() && isHighVolumeFunction) {
            applyHighVolumeMultiplier(simpleFeeContext, result);
        } else {
            // Apply congestion multiplier if available
            applyCongestionMultiplier(txnBody, simpleFeeContext, result, functionality);
        }

        return result;
    }

    /**
     * Applies the congestion multiplier to the fee result.
     * Gets the ReadableStoreFactory from the FeeContext implementation.
     *
     * @param txnBody the transaction body
     * @param simpleFeeContext the simple fee context
     * @param result the base fee result
     */
    private void applyCongestionMultiplier(
            @NonNull final TransactionBody txnBody,
            @NonNull final SimpleFeeContext simpleFeeContext,
            @NonNull final FeeResult result,
            @NonNull final HederaFunctionality functionality) {
        // For standalone fee calculator simpleFeeContext.feeContext() is null
        if (simpleFeeContext.feeContext() == null || congestionMultipliers == null) {
            return;
        }
        final var feeContext = simpleFeeContext.feeContext();
        final long congestionMultiplier =
                congestionMultipliers.maxCurrentMultiplier(txnBody, functionality, feeContext.readableStoreFactory());
        if (congestionMultiplier <= 1) {
            return;
        }
        result.applyMultiplier(congestionMultiplier, 1);
    }

    /**
     * Applies the high-volume pricing multiplier to the total fee based on throttle utilization.
     * This is applied after total fee is calculated.
     * Per HIP-1313, the multiplier is calculated from the pricing curve defined in the fee schedule.
     *
     * @param feeContext the fee context
     * @param result the fee result to modify
     */
    private void applyHighVolumeMultiplier(
            @NonNull final SimpleFeeContext feeContext, @NonNull final FeeResult result) {
        // For standalone fee calculator simpleFeeContext.feeContext() is null
        if (feeContext.feeContext() == null) {
            return;
        }
        final var rawMultiplier = highVolumeRawMultiplier(feeContext.body(), requireNonNull(feeContext.feeContext()));
        result.applyMultiplier(rawMultiplier, HIGH_VOLUME_MULTIPLIER_SCALE);
        result.setHighVolumeMultiplier(rawMultiplier);
    }

    @Override
    public long getExtraFee(Extra extra) {
        return feeSchedule.extras().stream()
                .filter(feeDefinition -> feeDefinition.name() == extra)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Extra fee not found: " + extra))
                .fee();
    }

    @Override
    public long highVolumeRawMultiplier(@NonNull final TransactionBody txnBody, @NonNull final FeeContext feeContext) {
        final var functionality = feeContext.functionality();
        if (!txnBody.highVolume() || !HIGH_VOLUME_PRICING_FUNCTIONS.contains(functionality)) {
            return DEFAULT_HIGH_VOLUME_MULTIPLIER;
        }
        final ServiceFeeDefinition serviceFeeDefinition = lookupServiceFee(feeSchedule, functionality);
        if (serviceFeeDefinition == null || serviceFeeDefinition.highVolumeRates() == null) {
            log.error(" {} - No high volume rates defined for {}", ALERT_MESSAGE, functionality);
            return DEFAULT_HIGH_VOLUME_MULTIPLIER;
        }
        final int utilizationPercentBasisPoints = feeContext.getHighVolumeThrottleUtilization(functionality);
        return HighVolumePricingCalculator.calculateMultiplier(
                serviceFeeDefinition.highVolumeRates(), utilizationPercentBasisPoints);
    }

    /**
     * Default implementation for query fee calculation.
     *
     * @param query The query to calculate fees for
     * @param simpleFeeContext the query context
     * @return Never returns normally
     * @throws UnsupportedOperationException always
     */
    @NonNull
    @Override
    public FeeResult calculateQueryFee(@NonNull final Query query, @NonNull final SimpleFeeContext simpleFeeContext) {
        final var result = new FeeResult();
        final var queryFeeCalculator = queryFeeCalculators.get(query.query().kind());
        queryFeeCalculator.accumulateNodePayment(query, simpleFeeContext, result, feeSchedule);
        return result;
    }
}
