// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fees;

import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;
import static org.hiero.hapi.support.fees.Extra.SIGNATURES;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.workflows.QueryContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
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
            @NonNull final FeeResult result,
            @NonNull final Iterable<ExtraFeeReference> extras,
            final long signatures,
            final long bytes) {
        for (final ExtraFeeReference ref : extras) {
            final long used =
                    switch (ref.name()) {
                        case SIGNATURES -> signatures;
                        case BYTES -> bytes;
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
     *
     * @param txnBody the transaction body
     * @param feeContext the fee context containing signature count and full transaction bytes
     * @return the calculated fee result
     */
    @NonNull
    @Override
    public FeeResult calculateTxFee(@NonNull final TransactionBody txnBody, @Nullable final FeeContext feeContext) {
        // Extract primitive counts (no allocations)
        final long signatures = feeContext != null ? feeContext.numTxnSignatures() : 0;
        // Get full transaction size in bytes (includes body, signatures, and all transaction data)
        final long bytes = feeContext != null ? feeContext.numTxnBytes() : 0;
        final var result = new FeeResult();

        // Add node base and extras (bytes and payer signatures)
        result.setNodeBaseFeeTinycents(feeSchedule.node().baseFee());
        addNodeExtras(result, feeSchedule.node().extras(), signatures, bytes);
        // Add network fee
        final int multiplier = feeSchedule.network().multiplier();
        result.setNetworkMultiplier(multiplier);

        final var serviceFeeCalculator =
                serviceFeeCalculators.get(txnBody.data().kind());
        serviceFeeCalculator.accumulateServiceFee(txnBody, feeContext, result, feeSchedule);

        // Apply high-volume pricing multiplier if applicable (HIP-1313)
        if (txnBody.highVolume() && feeContext != null) {
            applyHighVolumeMultiplier(txnBody, feeContext, result);
        }

        return result;
    }

    /**
     * Applies the high-volume pricing multiplier to the service fee based on throttle utilization.
     * Per HIP-1313, the multiplier is calculated from the pricing curve defined in the fee schedule.
     *
     * @param txnBody the transaction body
     * @param feeContext the fee context
     * @param result the fee result to modify
     */
    private void applyHighVolumeMultiplier(
            @NonNull final TransactionBody txnBody,
            @NonNull final FeeContext feeContext,
            @NonNull final FeeResult result) {
        // Get the HederaFunctionality for this transaction
        final HederaFunctionality functionality = mapToFunctionality(txnBody);
        if (functionality == null) {
            return;
        }

        // Look up the service fee definition to get the high volume rates
        final ServiceFeeDefinition serviceFeeDefinition = lookupServiceFee(feeSchedule, functionality);
        if (serviceFeeDefinition == null || serviceFeeDefinition.highVolumeRates() == null) {
            return;
        }

        // Get the current throttle utilization
        final int utilizationPercentage = feeContext.getHighVolumeThrottleUtilization(functionality);

        // Calculate the multiplier based on the pricing curve
        final long rawMultiplier = HighVolumePricingCalculator.calculateMultiplier(
                serviceFeeDefinition.highVolumeRates(), utilizationPercentage);

        // Apply the multiplier to the service fee
        // The raw multiplier is scaled by MULTIPLIER_SCALE (1,000) and represents the effective multiplier
        // So effective multiplier = rawMultiplier / MULTIPLIER_SCALE
        // We apply this to the service fee: newServiceFee = serviceFee * (rawMultiplier / MULTIPLIER_SCALE)
        // Since rawMultiplier is at least MULTIPLIER_SCALE (1000) for 1.0x, we need to replace the service fee
        final long multipliedServiceFee = (result.service * rawMultiplier) / HighVolumePricingCalculator.MULTIPLIER_SCALE;

        // Replace the service fee with the multiplied amount
        // We subtract the original service fee and add the new multiplied fee
        final long additionalFee = multipliedServiceFee - result.service;
        if (additionalFee > 0) {
            result.addServiceFee(1, additionalFee);
        }
    }

    /**
     * Maps a TransactionBody to its corresponding HederaFunctionality.
     *
     * @param txnBody the transaction body
     * @return the HederaFunctionality, or null if unknown
     */
    @Nullable
    private HederaFunctionality mapToFunctionality(@NonNull final TransactionBody txnBody) {
        return switch (txnBody.data().kind()) {
            case CRYPTO_CREATE_ACCOUNT -> HederaFunctionality.CRYPTO_CREATE;
            case CRYPTO_APPROVE_ALLOWANCE -> HederaFunctionality.CRYPTO_APPROVE_ALLOWANCE;
            case CONSENSUS_CREATE_TOPIC -> HederaFunctionality.CONSENSUS_CREATE_TOPIC;
            case FILE_CREATE -> HederaFunctionality.FILE_CREATE;
            case TOKEN_CREATION -> HederaFunctionality.TOKEN_CREATE;
            case TOKEN_MINT -> HederaFunctionality.TOKEN_MINT;
            case TOKEN_ASSOCIATE -> HederaFunctionality.TOKEN_ASSOCIATE_TO_ACCOUNT;
            case TOKEN_AIRDROP -> HederaFunctionality.TOKEN_AIRDROP;
            case TOKEN_CLAIM_AIRDROP -> HederaFunctionality.TOKEN_CLAIM_AIRDROP;
            case SCHEDULE_CREATE -> HederaFunctionality.SCHEDULE_CREATE;
            case CONTRACT_CREATE_INSTANCE -> HederaFunctionality.CONTRACT_CREATE;
            default -> null;
        };
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
     * @param queryContext the query context
     * @return Never returns normally
     * @throws UnsupportedOperationException always
     */
    @Override
    public long calculateQueryFee(@NonNull final Query query, @NonNull final QueryContext queryContext) {
        final var result = new FeeResult();
        final var queryFeeCalculator = queryFeeCalculators.get(query.query().kind());
        queryFeeCalculator.accumulateNodePayment(query, queryContext, result, feeSchedule);
        return result.totalTinycents();
    }
}
