// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers.transfer.customfees;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE;
import static com.hedera.node.app.service.token.impl.handlers.transfer.customfees.CustomFeeExemptions.isPayerExempt;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.transaction.CustomFee;
import com.hedera.hapi.node.transaction.FixedFee;
import com.hedera.node.app.spi.workflows.HandleException;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Utility class for custom fee adjustments.
 */
public class AdjustmentUtils {
    /**
     * Factory for creating adjustments map for a given token.
     */
    public static final Function<TokenID, Map<AccountID, Long>> ADJUSTMENTS_MAP_FACTORY =
            ignore -> new LinkedHashMap<>();

    private AdjustmentUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }

    /**
     * This method is used to adjust the balance changes for a custom fee. It is used for
     * fractional fees to calculate the amount to be charged.
     * @param numerator The numerator of the fraction
     * @param denominator The denominator of the fraction
     * @param amount The given units transferred
     * @return The amount to be charged
     * @throws ArithmeticException If denominator is 0
     */
    public static long safeFractionMultiply(final long numerator, final long denominator, final long amount)
            throws ArithmeticException {
        if (amount != 0 && numerator > Long.MAX_VALUE / amount) {
            return BigInteger.valueOf(amount)
                    .multiply(BigInteger.valueOf(numerator))
                    .divide(BigInteger.valueOf(denominator))
                    .longValueExact();
        } else {
            return numerator * amount / denominator;
        }
    }

    /**
     * Given the token denomination, fee collector and the amount to be charged, this method
     * returns the {@link FixedFee} representation of custom fee.
     * @param unitsToCollect The amount to be charged
     * @param tokenDenomination The token denomination
     * @param feeCollector The fee collector
     * @param allCollectorsAreExempt Whether all collectors are exempt
     * @return The {@link FixedFee} representation of custom fee
     */
    public static CustomFee asFixedFee(
            final long unitsToCollect,
            final TokenID tokenDenomination,
            final AccountID feeCollector,
            final boolean allCollectorsAreExempt) {
        Objects.requireNonNull(feeCollector);
        final var spec = FixedFee.newBuilder()
                .denominatingTokenId(tokenDenomination)
                .amount(unitsToCollect)
                .build();
        return CustomFee.newBuilder()
                .fixedFee(spec)
                .feeCollectorAccountId(feeCollector)
                .allCollectorsAreExempt(allCollectorsAreExempt)
                .build();
    }

    /**
     * Adjusts a HTS fee. If the fee is self-denominated, it should not trigger custom fees again
     * So add the adjustment to previous level transaction. If the fee is not self-denominated,
     * it should trigger custom fees again. So add the adjustment to next level transaction.
     *
     * @param result            The {@link AssessmentResult} object
     * @param sender            The sender account
     * @param collector         The fee collector
     * @param token             the token
     * @param amount            The amount to be charged
     * @param denominatingToken The token denomination
     */
    public static void adjustHtsFees(
            final AssessmentResult result,
            final AccountID sender,
            final AccountID collector,
            final Token token,
            final long amount,
            final TokenID denominatingToken) {
        final var newHtsAdjustments = result.getHtsAdjustments();
        final var inputHtsAdjustments = result.getMutableInputBalanceAdjustments();

        // If the fee is self-denominated, we don't need it to trigger next level custom fees
        // So add assessments in given input transaction body.
        if (token.tokenId().equals(denominatingToken)) {
            addHtsAdjustment(inputHtsAdjustments, sender, collector, amount, denominatingToken);
        } else {
            // Any change that might trigger next level custom fees should be added to next
            // level transaction adjustments
            addHtsAdjustment(newHtsAdjustments, sender, collector, amount, denominatingToken);
        }
    }

    /**
     * Adds HTS adjustment to given adjustments map. It makes 2 adjustments a debit
     * for sender and credit to collector
     * If there is already an entry merges the new change with it, otherwise creates a new entry.
     * @param htsAdjustments given adjustments map
     * @param sender sender account
     * @param collector collector account
     * @param amount amount to be charged
     * @param denominatingToken token denomination
     */
    private static void addHtsAdjustment(
            final Map<TokenID, Map<AccountID, Long>> htsAdjustments,
            final AccountID sender,
            final AccountID collector,
            final long amount,
            final TokenID denominatingToken) {
        final var denominatingTokenMap = htsAdjustments.computeIfAbsent(denominatingToken, ADJUSTMENTS_MAP_FACTORY);
        denominatingTokenMap.merge(sender, -amount, AdjustmentUtils::addExactOrThrow);
        denominatingTokenMap.merge(collector, amount, AdjustmentUtils::addExactOrThrow);
        htsAdjustments.put(denominatingToken, denominatingTokenMap);
    }

    /**
     * Given a list of changes for a specific token, filters all credits and returns them.
     * @param tokenIdChanges The list of changes for a specific token
     * @return The list of credits
     */
    public static Map<AccountID, Long> getFungibleTokenCredits(final Map<AccountID, Long> tokenIdChanges) {
        final var credits = new LinkedHashMap<AccountID, Long>();
        for (final var entry : tokenIdChanges.entrySet()) {
            final var account = entry.getKey();
            final var amount = entry.getValue();
            if (amount > 0) {
                credits.put(account, amount);
            }
        }
        return credits;
    }

    /**
     * Given a list of changes for a specific token, filters for the debits not exempt from the given fee.
     * @param tokenIdChanges The list of changes for a specific token
     * @return The map of non-exempt debits (in absolute value)
     */
    public static Map<AccountID, Long> getNonExemptTokenDebits(
            @NonNull final Map<AccountID, Long> tokenIdChanges,
            @NonNull final Token token,
            @NonNull final CustomFee fee) {
        final var nonExemptDebits = new LinkedHashMap<AccountID, Long>();
        for (final var entry : tokenIdChanges.entrySet()) {
            final var account = entry.getKey();
            final var amount = entry.getValue();
            if (amount < 0 && !isPayerExempt(token, fee, account)) {
                nonExemptDebits.put(account, -amount);
            }
        }
        return nonExemptDebits;
    }

    /**
     * Given a list of changes for a specific token, filters all fungible credits including hbar or
     * fungible token balances for a given beneficiary and returns them.
     * @param result The {@link AssessmentResult} object
     * @param sender The sender of the nft
     * @return The list of credits
     */
    public static List<ExchangedValue> getFungibleCredits(final AssessmentResult result, final AccountID sender) {
        final var tokenChanges = result.getImmutableInputTokenAdjustments();
        // get all the fungible changes that are credited to the sender of nft in the same transaction.
        // this includes hbar and fungible token balances
        final var credits = new ArrayList<ExchangedValue>();
        for (final var entry : result.getImmutableInputHbarAdjustments().entrySet()) {
            final var account = entry.getKey();
            final var amount = entry.getValue();
            if (amount > 0 && account.equals(sender)) {
                credits.add(new ExchangedValue(sender, null, amount));
            }
        }
        for (final var entry : tokenChanges.entrySet()) {
            final var token = entry.getKey();
            final var map = entry.getValue();
            if (map.containsKey(sender) && map.get(sender) > 0) {
                credits.add(new ExchangedValue(sender, token, map.get(sender)));
            }
        }
        return credits;
    }

    /**
     * Represents the exchanged value between accounts. It can be hbar or fungible token adjustments.
     * It is used to track the credits that can be used to deduct the custom royalty fees for an NFT transfer.
     * If there are no fungible units to the receiver, and if there is a fallback fee on NFT then receiver
     * should pay the fallback fee.
     * @param account The account ID of the receiver
     * @param tokenId The token ID of the fungible token
     * @param amount The amount exchanged
     */
    public record ExchangedValue(AccountID account, TokenID tokenId, long amount) {}

    /**
     * Adjusts hbar fees. It makes 2 adjustments a debit for sender and credit to collector.
     * If there is already an entry merges the new change with it, otherwise creates a new entry.
     * @param result The {@link AssessmentResult} object
     * @param sender The sender account
     * @param hbarFee The {@link CustomFee} object
     */
    public static void adjustHbarFees(final AssessmentResult result, final AccountID sender, final CustomFee hbarFee) {
        final var hbarAdjustments = result.getHbarAdjustments();
        final var collector = hbarFee.feeCollectorAccountId();
        final var fixedSpec = hbarFee.fixedFee();
        if (fixedSpec != null) {
            final var amount = fixedSpec.amount();
            hbarAdjustments.merge(sender, -amount, AdjustmentUtils::addExactOrThrow);
            hbarAdjustments.merge(collector, amount, AdjustmentUtils::addExactOrThrow);
        }
    }

    /**
     * Adds two longs and throws an exception if the result overflows.
     * @param addendA The first long
     * @param addendB The second long
     * @return The sum of the two longs
     */
    public static long addExactOrThrow(final long addendA, final long addendB) {
        return addExactOrThrowReason(addendA, addendB, INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE);
    }

    /**
     * Adds two longs and throws an exception if the result overflows.
     * @param addendA The first long
     * @param addendB The second long
     * @param failureReason The reason for the failure
     * @return The sum of the two longs
     */
    public static long addExactOrThrowReason(
            final long addendA, final long addendB, @NonNull final ResponseCodeEnum failureReason) {
        try {
            return Math.addExact(addendA, addendB);
        } catch (final ArithmeticException ignore) {
            throw new HandleException(failureReason);
        }
    }
}
