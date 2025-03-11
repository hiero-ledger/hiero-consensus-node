// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers.transfer.customfees;

import static com.hedera.node.app.service.token.impl.handlers.transfer.customfees.AdjustmentUtils.adjustHbarFees;
import static com.hedera.node.app.service.token.impl.handlers.transfer.customfees.AdjustmentUtils.adjustHtsFees;
import static com.hedera.node.app.service.token.impl.handlers.transfer.customfees.CustomFeeExemptions.isPayerExempt;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.transaction.AssessedCustomFee;
import com.hedera.hapi.node.transaction.CustomFee;
import com.hedera.hapi.node.transaction.FixedCustomFee;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Assesses fixed fees in a custom fee.
 * All the custom fees assessed from fixed fee will be added to the {@link AssessmentResult}
 * which will be used to create next level of transaction body to assess the next level of custom fees.
 * @see CustomFeeAssessor
 */
@Singleton
public class CustomFixedFeeAssessor {
    /**
     * Constructs a {@link CustomFixedFeeAssessor} instance.
     */
    @Inject
    public CustomFixedFeeAssessor() {
        // For Dagger injection
    }

    /**
     * Assesses fixed fees in a custom fee.
     * @param token the custom fee metadata for the token
     * @param sender the sender of the transaction, which will be payer for custom fees
     * @param result the assessment result which will be used to create next level of transaction body
     */
    public void assessFixedFees(
            @NonNull final Token token, @NonNull final AccountID sender, final AssessmentResult result) {
        for (final var fee : token.customFees()) {
            if (fee.fee().kind().equals(CustomFee.FeeOneOfType.FIXED_FEE)) {
                final var collector = fee.feeCollectorAccountId();
                if (sender.equals(collector)) {
                    continue;
                }
                // This is a top-level fixed fee, not a fallback royalty fee
                assessFixedFee(token, sender, fee, result);
            }
        }
    }

    /**
     * Fixed fee can be either a hbar or hts fee. This method assesses the fixed fee and adds the assessed fee to the
     * {@link AssessmentResult}.
     *
     * @param token  the custom fee metadata for the token
     * @param sender the sender of the transaction, which will be payer for custom fees
     * @param fee    the custom fee to be assessed
     * @param result the assessment result which will be used to create next level of transaction body
     */
    public void assessFixedFee(
            final Token token, final AccountID sender, final CustomFee fee, final AssessmentResult result) {
        if (isPayerExempt(token, fee, sender)) {
            return;
        }
        final var fixedFeeSpec = fee.fixedFeeOrThrow();
        if (!fixedFeeSpec.hasDenominatingTokenId()) {
            assessHbarFees(sender, fee, result, true);
        } else {
            assessHTSFees(sender, token, fee, result, true);
        }
    }

    /**
     * Set Transactions custom fees as assessed.
     * <p>
     * Note: Transaction custom fees can be only fixed fees. Any transaction that dispatch
     * crypto transfer to assess custom fees should provide the toplevel fees in the handle metadata
     *
     * @param fixedCustomFee simple transfer body representing the custom fixed fee
     * @param result assessment result
     */
    public void setTransactionFeesAsAssessed(
            final AccountID payer, final FixedCustomFee fixedCustomFee, final AssessmentResult result) {
        if (fixedCustomFee.hasFixedFee() && fixedCustomFee.hasFeeCollectorAccountId()) {
            final var fixedFee = fixedCustomFee.fixedFee();
            final var customFee = CustomFee.newBuilder()
                    .feeCollectorAccountId(fixedCustomFee.feeCollectorAccountId())
                    .fixedFee(fixedFee)
                    .build();

            if (fixedFee.hasDenominatingTokenId()) {
                final var token = Token.newBuilder()
                        .tokenId(fixedFee.denominatingTokenId())
                        .build();
                assessHTSFees(payer, token, customFee, result, false);
            } else {
                assessHbarFees(payer, customFee, result, false);
            }
        }
    }

    /**
     * Assesses if the fixed fee is a hbar fee and adds the assessed fee debit and credit
     * to the {@link AssessmentResult}.
     * @param sender the sender of the transaction, which will be payer for custom fees
     * @param hbarFee the hbar fee to be assessed
     * @param result the assessment result which will be used to create next level of transaction body
     */
    private void assessHbarFees(
            @NonNull final AccountID sender,
            @NonNull final CustomFee hbarFee,
            @NonNull final AssessmentResult result,
            boolean doAdjustments) {
        final var collector = hbarFee.feeCollectorAccountId();
        final var fixedSpec = hbarFee.fixedFee();
        final var amount = fixedSpec.amount();

        if (doAdjustments) {
            adjustHbarFees(result, sender, hbarFee);
        }
        // add all assessed fees for transaction record
        result.addAssessedCustomFee(AssessedCustomFee.newBuilder()
                .effectivePayerAccountId(sender)
                .amount(amount)
                .feeCollectorAccountId(collector)
                .build());
    }

    /**
     * Assesses if the fixed fee is a hts fee and adds the assessed fee debit and credit
     * to the {@link AssessmentResult}.
     * @param sender the sender of the transaction, which will be payer for custom fees
     * @param token token
     * @param htsFee the hts fee to be assessed
     * @param result the assessment result which will be used to create next level of transaction body
     */
    private void assessHTSFees(
            @NonNull final AccountID sender,
            @NonNull final Token token,
            @NonNull final CustomFee htsFee,
            @NonNull final AssessmentResult result,
            boolean doAdjustments) {
        final var collector = htsFee.feeCollectorAccountIdOrThrow();
        final var fixedFeeSpec = htsFee.fixedFeeOrThrow();
        final var amount = fixedFeeSpec.amount();
        final var denominatingToken = fixedFeeSpec.denominatingTokenIdOrThrow();
        if (doAdjustments) {
            adjustHtsFees(result, sender, collector, token, amount, denominatingToken);
        }
        // add all assessed fees for transaction record
        result.addAssessedCustomFee(AssessedCustomFee.newBuilder()
                .effectivePayerAccountId(sender)
                .amount(amount)
                .feeCollectorAccountId(collector)
                .tokenId(fixedFeeSpec.denominatingTokenId())
                .build());
    }
}
