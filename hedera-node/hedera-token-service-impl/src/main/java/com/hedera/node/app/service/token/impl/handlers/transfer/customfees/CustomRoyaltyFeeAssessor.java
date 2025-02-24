// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers.transfer.customfees;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE;
import static com.hedera.node.app.service.token.impl.handlers.transfer.customfees.AdjustmentUtils.ADJUSTMENTS_MAP_FACTORY;
import static com.hedera.node.app.service.token.impl.handlers.transfer.customfees.AdjustmentUtils.asFixedFee;
import static com.hedera.node.app.service.token.impl.handlers.transfer.customfees.AdjustmentUtils.getFungibleCredits;
import static com.hedera.node.app.service.token.impl.handlers.transfer.customfees.AdjustmentUtils.safeFractionMultiply;
import static com.hedera.node.app.service.token.impl.handlers.transfer.customfees.AssessmentResult.HBAR_TOKEN_ID;
import static com.hedera.node.app.service.token.impl.handlers.transfer.customfees.CustomFeeExemptions.isPayerExempt;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.transaction.AssessedCustomFee;
import com.hedera.hapi.node.transaction.CustomFee;
import com.hedera.node.app.spi.workflows.HandleException;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Assesses royalty fees for given token transfer. Royalty fees are only charged for NON_FUNGIBLE_TOKEN
 * type. If the nft has already paid royalty in any level of CryptoTransfer, it will not be charged
 * custom fees again.
 * If there is fungible exchange value to the receiver of the NFT,
 */
@Singleton
public class CustomRoyaltyFeeAssessor {

    private final CustomFixedFeeAssessor fixedFeeAssessor;

    /**
     * Constructs a {@link CustomRoyaltyFeeAssessor} instance.
     * @param fixedFeeAssessor the fixed fee assessor
     */
    @Inject
    public CustomRoyaltyFeeAssessor(final CustomFixedFeeAssessor fixedFeeAssessor) {
        this.fixedFeeAssessor = fixedFeeAssessor;
    }

    /**
     * Assesses royalty fees for given token transfer.
     *
     * @param token the token
     * @param sender the sender
     * @param receiver the receiver
     * @param result the assessment result
     */
    // Suppressing the warning about using two "continue" statements and having unused variable
    @SuppressWarnings({"java:S1854", "java:S135"})
    public void assessRoyaltyFees(
            @NonNull final Token token,
            @NonNull final AccountID sender,
            @NonNull final AccountID receiver,
            @NonNull final AssessmentResult result) {
        final var tokenId = token.tokenId();
        // In a given CryptoTransfer, we only charge royalties to an account once per token type; so
        // even if 0.0.A is sending multiple NFTs of type 0.0.T in a single transfer, we only deduct
        // royalty fees once from the value it receives in return.
        if (result.getRoyaltiesPaid().contains(Pair.of(sender, tokenId))) {
            return;
        }

        // get all hbar and fungible token changes from given input to the current level
        final var exchangedValue = getFungibleCredits(result, sender);
        for (final var fee : token.customFees()) {
            final var collector = fee.feeCollectorAccountId();
            if (!fee.fee().kind().equals(CustomFee.FeeOneOfType.ROYALTY_FEE)) {
                continue;
            }
            final var royaltyFee = fee.royaltyFeeOrThrow();
            // If there are no fungible units to the receiver, then  if there is a fallback fee
            // then receiver should pay the fallback fee
            if (exchangedValue.isEmpty()) {
                if (!royaltyFee.hasFallbackFee()) {
                    continue;
                }
                // Skip if the receiver has already paid the fallback fee for this token
                if (result.getRoyaltiesPaid().contains(Pair.of(receiver, tokenId))) {
                    continue;
                }
                final var fallback = royaltyFee.fallbackFeeOrThrow();
                final var fallbackFee = asFixedFee(
                        fallback.amount(), fallback.denominatingTokenId(), collector, fee.allCollectorsAreExempt());
                fixedFeeAssessor.assessFixedFee(token, receiver, fallbackFee, result);
            } else {
                if (!isPayerExempt(token, fee, sender)) {
                    chargeRoyalty(exchangedValue, fee, result);
                }
            }
        }
        // We check this outside the for loop above because a sender should only be marked as paid royalty, after
        // assessing
        // all the fees for the given token. If a sender is sending multiple NFTs of the same token, royalty fee
        // should be paid only once.
        // We don't want to charge the fallback fee for each nft transfer, if the receiver has already
        // paid it for this token.

        if (exchangedValue.isEmpty()) {
            // Receiver pays fallback fees
            result.addToRoyaltiesPaid(Pair.of(receiver, tokenId));
        } else {
            // Sender effectively pays percent royalties. Here we don't check isPayerExempt because
            // the sender could be exempt for one fee on token, but not other fees(if any) on the same token.
            result.addToRoyaltiesPaid(Pair.of(sender, tokenId));
        }
    }

    /**
     * Charges royalty fees to the receiver of the NFT. If the receiver is not receiving any fungible value
     * then the fallback fee is charged to the receiver balance. Otherwise, the royalty fee is charged to the
     * credit value of the receiver.
     * @param exchangedValues fungible values exchanged to the receiver
     * @param fee royalty fee
     * @param result assessment result
     */
    private void chargeRoyalty(
            @NonNull List<AdjustmentUtils.ExchangedValue> exchangedValues,
            @NonNull final CustomFee fee,
            @NonNull final AssessmentResult result) {
        for (final var exchange : exchangedValues) {
            final var account = exchange.account();
            final var amount = exchange.amount();
            final var denom = exchange.tokenId();

            final var royaltySpec = fee.royaltyFeeOrThrow();
            final var feeCollector = fee.feeCollectorAccountIdOrThrow();

            try {
                final var royalty = safeFractionMultiply(
                        royaltySpec.exchangeValueFraction().numerator(),
                        royaltySpec.exchangeValueFraction().denominator(),
                        amount);
                validateTrue(royalty <= amount, INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE);

                /* The id of the charging token is only used here to avoid recursively charging
                on fees charged in the units of their denominating token; but this is a credit,
                hence the id is irrelevant, and we can use null. */
                if (denom != null) {
                    // exchange is for token
                    redirectHtsRoyaltyFee(result, account, feeCollector, royalty, denom);
                } else {
                    // exchange is for hbar
                    redirectHbarRoyaltyFee(result, account, feeCollector, royalty);
                }

                final var assessedCustomFeeBuilder = AssessedCustomFee.newBuilder()
                        .amount(royalty)
                        .feeCollectorAccountId(feeCollector)
                        .effectivePayerAccountId(account);
                if (denom != null) {
                    // exchange is for token
                    result.addAssessedCustomFee(
                            assessedCustomFeeBuilder.tokenId(denom).build());
                } else {
                    // exchange is for hbar
                    result.addAssessedCustomFee(assessedCustomFeeBuilder.build());
                }
            } catch (final ArithmeticException e) {
                throw new HandleException(INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE);
            }
        }
    }

    /**
     * Redirects royalty fee from the sender to the fee collector.
     * @param result assessment result
     * @param account sender
     * @param feeCollector fee collector
     * @param royalty royalty fee
     * @param denom token id
     */
    private void redirectHtsRoyaltyFee(
            @NonNull final AssessmentResult result,
            @NonNull final AccountID account,
            @NonNull final AccountID feeCollector,
            final long royalty,
            @NonNull final TokenID denom) {
        final var htsAdjustments = result.getHtsAdjustments().computeIfAbsent(denom, ADJUSTMENTS_MAP_FACTORY);
        final var mutableInputHtsAdjustments =
                result.getMutableInputBalanceAdjustments().get(denom);
        mutableInputHtsAdjustments.merge(account, -royalty, AdjustmentUtils::addExactOrThrow);
        htsAdjustments.merge(feeCollector, royalty, AdjustmentUtils::addExactOrThrow);
    }

    /**
     * Redirects royalty fee from the sender to the fee collector.
     * @param result assessment result
     * @param account sender
     * @param feeCollector fee collector
     * @param royalty royalty fee
     */
    private void redirectHbarRoyaltyFee(
            @NonNull final AssessmentResult result,
            @NonNull final AccountID account,
            @NonNull final AccountID feeCollector,
            final long royalty) {
        final var hbarAdjustments = result.getHbarAdjustments();
        final var mutableHbarAdjustments =
                result.getMutableInputBalanceAdjustments().get(HBAR_TOKEN_ID);
        mutableHbarAdjustments.merge(account, -royalty, AdjustmentUtils::addExactOrThrow);
        hbarAdjustments.merge(feeCollector, royalty, AdjustmentUtils::addExactOrThrow);
    }
}
