// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fees;

import static org.hiero.hapi.fees.FeeScheduleUtils.lookupExtraFee;
import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.transaction.Query;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.function.Function;
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
public abstract class AbstractSimpleFeeCalculator implements SimpleFeeCalculator {

    /**
     * Utility: Calculates extra fees from a list of ExtraFeeReferences.
     * Subclasses provide a lambda/method reference to map Extra enum to usage count.
     *
     * <p>This implements the common pattern: base + sum(overage * feePerUnit) where overage =
     * max(0, used - included).
     *
     * @param extras The list of extra fee references to process
     * @param feeSchedule The fee schedule to lookup extra fee costs
     * @param usageMapper Function that returns usage count for each Extra type
     * @return Total extra fees
     */
    protected static long calculateExtraFees(
            @NonNull final List<ExtraFeeReference> extras,
            @NonNull final FeeSchedule feeSchedule,
            @NonNull final Function<Extra, Long> usageMapper) {
        long total = 0;
        for (final var ref : extras) {
            final long used = usageMapper.apply(ref.name());
            if (used > ref.includedCount()) {
                final long overage = used - ref.includedCount();
                total += overage * lookupExtraFee(feeSchedule, ref).fee();
            }
        }
        return total;
    }

    /**
     * Utility: Calculates network fee from node fee and multiplier.
     * @param nodeFee The calculated node fee
     * @param multiplier The network multiplier from fee schedule
     * @return Network fee
     */
    protected static long calculateNetworkFee(long nodeFee, int multiplier) {
        return nodeFee * multiplier;
    }

    /**
     * Creates a builder for constructing usage mappers.
     * Usage example:
     * <pre>
     * var usageMapper = usageBuilder()
     *     .withSignatures(context.numTxnSignatures())
     *     .withKeys(keyCount)
     *     .build();
     * </pre>
     *
     * @return a new UsageBuilder instance
     */
    protected static UsageBuilder usageBuilder() {
        return new UsageBuilder();
    }

    /**
     * Fluent builder for creating Extra usage mappers per HIP-1261.
     * Provides named methods for each Extra type to clearly document which extras
     * a transaction uses.
     */
    protected static final class UsageBuilder {
        private final long[] counts = new long[Extra.values().length];

        private UsageBuilder() {}

        public UsageBuilder withSignatures(long count) {
            counts[Extra.SIGNATURES.ordinal()] = count;
            return this;
        }

        public UsageBuilder withBytes(long count) {
            counts[Extra.BYTES.ordinal()] = count;
            return this;
        }

        public UsageBuilder withKeys(long count) {
            counts[Extra.KEYS.ordinal()] = count;
            return this;
        }

        public UsageBuilder withTokenTypes(long count) {
            counts[Extra.TOKEN_TYPES.ordinal()] = count;
            return this;
        }

        public UsageBuilder withNftSerials(long count) {
            counts[Extra.NFT_SERIALS.ordinal()] = count;
            return this;
        }

        public UsageBuilder withAccounts(long count) {
            counts[Extra.ACCOUNTS.ordinal()] = count;
            return this;
        }

        public UsageBuilder withStandardFungibleTokens(long count) {
            counts[Extra.STANDARD_FUNGIBLE_TOKENS.ordinal()] = count;
            return this;
        }

        public UsageBuilder withStandardNonFungibleTokens(long count) {
            counts[Extra.STANDARD_NON_FUNGIBLE_TOKENS.ordinal()] = count;
            return this;
        }

        public UsageBuilder withCustomFeeFungibleTokens(long count) {
            counts[Extra.CUSTOM_FEE_FUNGIBLE_TOKENS.ordinal()] = count;
            return this;
        }

        public UsageBuilder withCustomFeeNonFungibleTokens(long count) {
            counts[Extra.CUSTOM_FEE_NON_FUNGIBLE_TOKENS.ordinal()] = count;
            return this;
        }

        public UsageBuilder withCreatedAutoAssociations(long count) {
            counts[Extra.CREATED_AUTO_ASSOCIATIONS.ordinal()] = count;
            return this;
        }

        public UsageBuilder withCreatedAccounts(long count) {
            counts[Extra.CREATED_ACCOUNTS.ordinal()] = count;
            return this;
        }

        public UsageBuilder withCustomFee(long count) {
            counts[Extra.CUSTOM_FEE.ordinal()] = count;
            return this;
        }

        public UsageBuilder withGas(long count) {
            counts[Extra.GAS.ordinal()] = count;
            return this;
        }

        public UsageBuilder withAllowances(long count) {
            counts[Extra.ALLOWANCES.ordinal()] = count;
            return this;
        }

        public UsageBuilder withAirdrops(long count) {
            counts[Extra.AIRDROPS.ordinal()] = count;
            return this;
        }

        /**
         * Builds the usage mapper function.
         * @return Function that maps Extra types to their usage counts
         */
        public Function<Extra, Long> build() {
            return extra -> counts[extra.ordinal()];
        }
    }

    /**
     * Template method that implements the standard fee calculation pattern for transactions.
     * Subclasses call this from their calculateTxFee() implementation after building their usage mapper.
     *
     * <p>This method handles the common flow:
     * <ol>
     *   <li>Get fee schedule from context</li>
     *   <li>Look up service definition for the given functionality</li>
     *   <li>Calculate node fee (base + extras)</li>
     *   <li>Calculate service fee (base + extras)</li>
     *   <li>Calculate network fee (node × multiplier)</li>
     *   <li>Return FeeResult</li>
     * </ol>
     *
     * @param functionality The HederaFunctionality to calculate fees for
     * @param usageMapper Function mapping Extra types to their usage counts
     * @param context The transaction context providing state access
     * @return The calculated fee result
     */
    protected static FeeResult calculateStandardTxFee(
            @NonNull final HederaFunctionality functionality,
            @NonNull final Function<Extra, Long> usageMapper,
            @NonNull final SimpleFeeCalculator.TxContext context) {
        // Get fee schedule
        final var feeCalculator = context.feeCalculatorFactory().feeCalculator(SubType.DEFAULT);
        final var feeSchedule = feeCalculator.getSimpleFeesSchedule();
        final var serviceDef = lookupServiceFee(feeSchedule, functionality);

        // Calculate fees
        long nodeFee = feeSchedule.node().baseFee()
                + calculateExtraFees(feeSchedule.node().extras(), feeSchedule, usageMapper);

        long serviceFee = serviceDef.baseFee() + calculateExtraFees(serviceDef.extras(), feeSchedule, usageMapper);

        long networkFee = calculateNetworkFee(nodeFee, feeSchedule.network().multiplier());

        // Return result
        final var result = new FeeResult();
        result.node = nodeFee;
        result.network = networkFee;
        result.service = serviceFee;
        return result;
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
     * Subclasses should override this only if they actually support transaction fee calculation.
     *
     * @param feeContext The fee context containing transaction and state access
     * @return Never returns normally
     * @throws UnsupportedOperationException always
     */
    @Override
    @NonNull
    public FeeResult calculateTxFee(@NonNull FeeContext feeContext) {
        throw new UnsupportedOperationException(
                "Txn fee calculation not supported for " + getClass().getSimpleName());
    }

    /**
     * Default implementation for query fee calculation.
     * Subclasses should override this only if they actually support query fee calculation.
     *
     * @param query The query to calculate fees for
     * @param context The query context
     * @return Never returns normally
     * @throws UnsupportedOperationException always
     */
    @Override
    @NonNull
    public FeeResult calculateQueryFee(@NonNull final Query query, @Nullable final QueryContext context) {
        throw new UnsupportedOperationException(
                "Query fee calculation not supported for " + getClass().getSimpleName());
    }
}
