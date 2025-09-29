// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers.transfer.customfees;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CUSTOM_FEE_MUST_BE_POSITIVE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CUSTOM_FEE_OUTSIDE_NUMERIC_RANGE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE;
import static com.hedera.node.app.service.token.impl.handlers.transfer.customfees.AdjustmentUtils.ADJUSTMENTS_MAP_FACTORY;
import static com.hedera.node.app.service.token.impl.handlers.transfer.customfees.AdjustmentUtils.asFixedFee;
import static com.hedera.node.app.service.token.impl.handlers.transfer.customfees.AdjustmentUtils.getFungibleTokenCredits;
import static com.hedera.node.app.service.token.impl.handlers.transfer.customfees.AdjustmentUtils.getNonExemptTokenDebits;
import static com.hedera.node.app.service.token.impl.handlers.transfer.customfees.AdjustmentUtils.safeFractionMultiply;
import static com.hedera.node.app.service.token.impl.handlers.transfer.customfees.CustomFeeExemptions.isPayerExempt;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.transaction.AssessedCustomFee;
import com.hedera.hapi.node.transaction.CustomFee;
import com.hedera.hapi.node.transaction.FractionalFee;
import com.hedera.node.app.spi.workflows.HandleException;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Assesses fractional custom fees for given token transfer.
 * All fractional fees, that are not netOfTransfers will manipulate the given transaction body.
 * If netOfTransfers flag is set to false, the custom fee si reclaimed from the credits in
 * given transaction body.
 * If netOfTransfers flag is set to true the sender will pay the custom fees, else the receivers will pay custom fees.
 * This means that the fee will be charged from the sender account. This is done to avoid
 * manipulation of the given transaction.
 */
@Singleton
public class CustomFractionalFeeAssessor {
    private final CustomFixedFeeAssessor fixedFeeAssessor;

    /**
     * Constructs a {@link CustomFractionalFeeAssessor} instance.
     * @param fixedFeeAssessor the fixed fee assessor
     */
    @Inject
    public CustomFractionalFeeAssessor(CustomFixedFeeAssessor fixedFeeAssessor) {
        this.fixedFeeAssessor = fixedFeeAssessor;
    }

    /**
     * Calculates the custom fee amount that should be paid for given fraction fee.
     * If the fee is netOfTransfers the sender will pay the fee, otherwise the receiver(s) will effectively pay the fee,
     * as they are the accounts whose balances will be lower than if the fee had not existed.
     * If netOfTransfers is true the assessed fee will be accumulated for next level transaction body.
     * If netOfTransfers is false the assessed fee will be reclaimed from the credits in given transaction body.
     *
     * @param token  the fee meta
     * @param sender the sender, who might be payer for the fee if netOfTransfers is true
     * @param result the result
     */
    // Suppressing the warning about using two "continue" statements
    @SuppressWarnings("java:S135")
    public void assessFractionalFees(
            @NonNull final Token token, @NonNull final AccountID sender, @NonNull final AssessmentResult result) {
        final var denom = token.tokenIdOrThrow();

        final var nonMutableInputTokenTransfers = result.getImmutableInputTokenAdjustments();
        // get the initial units for this token change from given input.
        // This is needed to see the fraction of the adjustment to be charged as custom fee
        final var initialAdjustment = nonMutableInputTokenTransfers.get(denom).get(sender);
        // custom fees can't be assessed for credits
        validateTrue(initialAdjustment < 0, CUSTOM_FEE_MUST_BE_POSITIVE);

        long unitsLeft = -initialAdjustment;
        final var creditsForToken = getFungibleTokenCredits(nonMutableInputTokenTransfers.get(denom));
        for (final var fee : token.customFees()) {
            final var collector = fee.feeCollectorAccountId();
            // If the collector 0.0.C for a fractional fee is trying to send X units to
            // a receiver 0.0.R, then we want to let all X units go to 0.0.R, instead of
            // reclaiming some fraction of them
            if (!fee.fee().kind().equals(CustomFee.FeeOneOfType.FRACTIONAL_FEE) || sender.equals(collector)) {
                continue;
            }
            final var filteredOriginalCredits = filteredByExemptCredits(creditsForToken, token, fee);
            if (filteredOriginalCredits.isEmpty()) {
                continue;
            }
            final var fractionalFee = fee.fractionalFeeOrThrow();
            if (fractionalFee.netOfTransfers()) {
                // Assess net-of-transfers relative to the initial debit amount, not the amount of non-exempt credits
                long assessedAmount = netOfTransferAmountOwed(-initialAdjustment, fractionalFee);
                final var addedFee =
                        asFixedFee(assessedAmount, denom, fee.feeCollectorAccountId(), fee.allCollectorsAreExempt());
                fixedFeeAssessor.assessFixedFee(token, sender, addedFee, result);
            } else {
                final long totalDebits;
                final long nonExemptCredits;
                try {
                    totalDebits =
                            getNonExemptTokenDebits(nonMutableInputTokenTransfers.get(denom), token, fee)
                                    .values()
                                    .stream()
                                    .mapToLong(Long::longValue)
                                    .reduce(0L, Math::addExact);
                    nonExemptCredits = filteredOriginalCredits.values().stream()
                            .mapToLong(Long::longValue)
                            .reduce(0L, Math::addExact);
                } catch (Exception e) {
                    throw new HandleException(CUSTOM_FEE_OUTSIDE_NUMERIC_RANGE);
                }
                // Assess fee relative to the proportion of total debits that this debit accounted for
                final var nonNetAssessment = assessNonNetOfTransferForDebit(
                        -initialAdjustment, totalDebits, nonExemptCredits, fractionalFee);
                long assessedAmount = nonNetAssessment.amount();
                final var map =
                        result.getMutableInputBalanceAdjustments().computeIfAbsent(denom, ADJUSTMENTS_MAP_FACTORY);
                final var filteredRemainingCredits = filteredByExemptCredits(map, token, fee);
                // This has the side effect of reducing the filtered credits map
                final var reclaimResult = reclaim(assessedAmount, filteredRemainingCredits);
                final var unreclaimedAmount = reclaimResult.unreclaimedAmount();
                if (nonNetAssessment.isMinimum() && unreclaimedAmount > 0) {
                    throw new HandleException(INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE);
                }
                if (unreclaimedAmount == assessedAmount) {
                    // Nothing to do here, the fee rounded down to zero
                    continue;
                }
                final long collectedAmount = assessedAmount - unreclaimedAmount;
                unitsLeft -= collectedAmount;
                validateTrue(unitsLeft >= 0, INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE);
                // Adjust mutable input map with the updated credits
                map.putAll(filteredRemainingCredits);
                // And update the collector's credit with the collected amount
                map.merge(collector, collectedAmount, AdjustmentUtils::addExactOrThrow);
                result.getMutableInputBalanceAdjustments().put(denom, map);

                final var finalEffPayerNums = filteredOriginalCredits.keySet();
                final var finalEffPayerNumsArray = new AccountID[finalEffPayerNums.size()];

                // Add assessed custom fees to the result. This is needed to build transaction record
                // If there are multiple payers, record the details in the result. This is needed to construct
                // custom fee proposed transfers with hooks
                result.addAssessedFeeWithPayerDebits(
                        AssessedCustomFee.newBuilder()
                                .effectivePayerAccountId(finalEffPayerNums.toArray(finalEffPayerNumsArray))
                                .feeCollectorAccountId(collector)
                                .tokenId(denom)
                                .amount(collectedAmount)
                                .build(),
                        reclaimResult.paidByPayer());
            }
        }
    }

    /**
     * From the given adjustments, filters all credits whose payer is not exempt from custom fee.
     * Returns credits back if there are no credits whose payer is not exempt from custom fee.
     * If all credits are exempt from custom fee, returns empty map
     * @param adjustments the adjustments for a token
     * @param token the fee meta
     * @param fee the custom fee
     * @return the filtered credits whose payer is not exempt from custom fee
     */
    private Map<AccountID, Long> filteredByExemptCredits(
            @NonNull final Map<AccountID, Long> adjustments, @NonNull final Token token, @NonNull final CustomFee fee) {
        final var filteredCredits = new LinkedHashMap<AccountID, Long>();
        for (final var entry : adjustments.entrySet()) {
            final var account = entry.getKey();
            final var amount = entry.getValue();
            if (amount > 0 && !isPayerExempt(token, fee, account)) {
                filteredCredits.put(account, amount);
            }
        }
        return filteredCredits;
    }

    public record NonNetAssessment(long amount, boolean isMinimum) {}

    /**
     * Calculates the amount owned to be paid as fractional custom fee.
     *
     * @param activeDebit the debit triggering the fractional fee assessment
     * @param totalDebits the total debits for the token type with the fee in the transaction
     * @param nonExemptCredits units transferred in the transaction
     * @param fractionalFee the fractional fee
     * @return the amount owned to be paid as fractional custom fee
     */
    public NonNetAssessment assessNonNetOfTransferForDebit(
            final long activeDebit,
            final long totalDebits,
            final long nonExemptCredits,
            @NonNull final FractionalFee fractionalFee) {
        final long numerator = fractionalFee.fractionalAmountOrThrow().numerator();
        final long denominator = fractionalFee.fractionalAmountOrThrow().denominator();
        long nominalFee;
        boolean isMinimum = false;
        try {
            nominalFee = safeFractionMultiply(numerator, denominator, nonExemptCredits);
        } catch (final ArithmeticException e) {
            throw new HandleException(CUSTOM_FEE_OUTSIDE_NUMERIC_RANGE);
        }
        long effectiveFee = Math.max(nominalFee, fractionalFee.minimumAmount());
        if (effectiveFee > nominalFee) {
            isMinimum = true;
        }
        if (fractionalFee.maximumAmount() > 0) {
            effectiveFee = Math.min(effectiveFee, fractionalFee.maximumAmount());
        }
        return new NonNetAssessment(safeFractionMultiply(activeDebit, totalDebits, effectiveFee), isMinimum);
    }

    /**
     * Assesses the amount to be paid as net-of-transfers fractional custom fee.
     * @param givenUnits  units transferred in the transaction
     * @param fractionalFee the fractional fee
     * @return the amount owned to be paid as fractional custom fee
     */
    private long netOfTransferAmountOwed(final long givenUnits, @NonNull final FractionalFee fractionalFee) {
        final var numerator = fractionalFee.fractionalAmountOrThrow().numerator();
        final var denominator = fractionalFee.fractionalAmountOrThrow().denominator();
        var nominalFee = 0L;
        try {
            nominalFee = safeFractionMultiply(numerator, denominator, givenUnits);
        } catch (final ArithmeticException e) {
            throw new HandleException(CUSTOM_FEE_OUTSIDE_NUMERIC_RANGE);
        }
        long effectiveFee = Math.max(nominalFee, fractionalFee.minimumAmount());
        if (fractionalFee.maximumAmount() > 0) {
            effectiveFee = Math.min(effectiveFee, fractionalFee.maximumAmount());
        }
        return effectiveFee;
    }

    /**
     * Deducts the given amount from the given credits. If there are multiple credits to same account,
     * reclaims proportionally from each credit.
     * @param amount the amount to be reclaimed
     * @param credits the credits to be reclaimed from
     * @return the amount reclaimed
     */
    private ReclaimResult reclaim(final long amount, @NonNull final Map<AccountID, Long> credits) {
        long availableToReclaim = 0L;
        for (final var entry : credits.entrySet()) {
            availableToReclaim += entry.getValue();
            validateTrue(availableToReclaim >= 0, CUSTOM_FEE_OUTSIDE_NUMERIC_RANGE);
        }
        final long amountToReclaim = Math.min(amount, availableToReclaim);

        long amountReclaimed = 0L;
        final var paidByPayer = new LinkedHashMap<AccountID, Long>();
        for (final var entry : credits.entrySet()) {
            final var account = entry.getKey();
            final long creditAmount = entry.getValue();
            try {
                final long toReclaimHere = safeFractionMultiply(creditAmount, availableToReclaim, amountToReclaim);
                if (toReclaimHere != 0) {
                    credits.put(account, creditAmount - toReclaimHere);
                    amountReclaimed += toReclaimHere;
                    paidByPayer.merge(account, -toReclaimHere, Math::addExact);
                }
            } catch (final ArithmeticException e) {
                throw new HandleException(CUSTOM_FEE_OUTSIDE_NUMERIC_RANGE);
            }
        }
        //  Distribute any rounding remainder deterministically in iteration order
        if (amountReclaimed < amountToReclaim) {
            long leftToReclaim = amountToReclaim - amountReclaimed;
            for (final var entry : credits.entrySet()) {
                final var account = entry.getKey();
                final long creditAmount = entry.getValue();
                final long toReclaimHere = Math.min(creditAmount, leftToReclaim);
                if (toReclaimHere != 0L) {
                    credits.put(account, creditAmount - toReclaimHere);
                    amountReclaimed += toReclaimHere;
                    leftToReclaim -= toReclaimHere;
                    paidByPayer.merge(account, -toReclaimHere, Math::addExact);
                }
                if (leftToReclaim == 0) {
                    break;
                }
            }
        }
        final var unreclaimed = amount - amountReclaimed;
        return new ReclaimResult(unreclaimed, paidByPayer);
    }

    /**
     * A record to hold the result of reclaim operation. It contains the unreclaimed amount
     * and a map of amounts paid by each payer for fractional fee.
     *
     * @param unreclaimedAmount the amount that could not be reclaimed
     * @param paidByPayer a map of amounts paid by each payer
     */
    private record ReclaimResult(long unreclaimedAmount, Map<AccountID, Long> paidByPayer) {}
}
