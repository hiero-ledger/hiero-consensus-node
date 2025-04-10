// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers.transfer.customfees;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.FixedCustomFee;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.impl.handlers.BaseTokenHandler;
import com.hedera.node.app.spi.workflows.HandleException;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Predicate;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Assesses custom fees for a given crypto transfer transaction.
 */
@Singleton
public class CustomFeeAssessor extends BaseTokenHandler {
    private final CustomFixedFeeAssessor fixedFeeAssessor;
    private final CustomFractionalFeeAssessor fractionalFeeAssessor;
    private final CustomRoyaltyFeeAssessor royaltyFeeAssessor;
    private int initialNftChanges = 0;

    /**
     * Constructs a {@link CustomFeeAssessor} instance.
     * @param fixedFeeAssessor the fixed fee assessor
     * @param fractionalFeeAssessor the fractional fee assessor
     * @param royaltyFeeAssessor the royalty fee assessor
     */
    @Inject
    public CustomFeeAssessor(
            @NonNull final CustomFixedFeeAssessor fixedFeeAssessor,
            @NonNull final CustomFractionalFeeAssessor fractionalFeeAssessor,
            @NonNull final CustomRoyaltyFeeAssessor royaltyFeeAssessor) {
        this.fixedFeeAssessor = fixedFeeAssessor;
        this.fractionalFeeAssessor = fractionalFeeAssessor;
        this.royaltyFeeAssessor = royaltyFeeAssessor;
    }

    private int numNftTransfers(final CryptoTransferTransactionBody op) {
        final var tokenTransfers = op.tokenTransfers();
        var nftTransfers = 0;
        for (final var xfer : tokenTransfers) {
            nftTransfers += xfer.nftTransfers().size();
        }
        return nftTransfers;
    }

    /**
     * Assesses custom fees for a given crypto transfer transaction. The fees are assessed in the following order:
     * 1. Fixed fees
     * 2. Fractional fees (for fungible common tokens)
     * 3. Royalty fees (for non-fungible unique tokens)
     * @param sender the sender account ID
     * @param token the token
     * @param receiver the receiver account ID
     * @param result the assessment result
     * @param tokenRelStore the token relation store
     * @param accountStore the account store
     * @param autoCreationTest the auto-creation test
     */
    public void assess(
            final AccountID sender,
            final Token token,
            final AccountID receiver,
            final AssessmentResult result,
            final ReadableTokenRelationStore tokenRelStore,
            final ReadableAccountStore accountStore,
            final Predicate<AccountID> autoCreationTest) {
        fixedFeeAssessor.assessFixedFees(token, sender, result);

        revalidateAssessmentResult(result, tokenRelStore, accountStore, autoCreationTest);

        // A FUNGIBLE_COMMON token can have fractional fees but not royalty fees.
        // A NON_FUNGIBLE_UNIQUE token can have royalty fees but not fractional fees.
        // So check token type and do further assessment
        if (token.tokenType().equals(TokenType.FUNGIBLE_COMMON)) {
            fractionalFeeAssessor.assessFractionalFees(token, sender, result);
        } else {
            royaltyFeeAssessor.assessRoyaltyFees(token, sender, receiver, result);
        }
        revalidateAssessmentResult(result, tokenRelStore, accountStore, autoCreationTest);
    }

    /**
     * Sets the transaction fees as assessed for a given payer and custom fee.
     * This method updates the assessment result with the specified custom fee.
     * Note: This method does not adjust the payer's balance.
     *
     * @param payer The Account ID of the payer responsible for the custom fee.
     * @param fee The custom fee to be assessed.
     * @param result The assessment result to be updated with the custom fee.
     */
    public void setTransactionFeesAsAssessed(
            @NonNull final AccountID payer, @NonNull final FixedCustomFee fee, @NonNull final AssessmentResult result) {
        fixedFeeAssessor.setTransactionFeesAsAssessed(payer, fee, result);
    }

    /**
     * Validates the assessment result after each type of fees assessment(fixed fees, fractional fees, royalty fees).
     * The validation consists of two steps:
     * 1. Ensures that the sender account has sufficient balance to pay the custom fee
     * 2. Ensures that the sender account is associated with the token
     * @param result the assessment result
     * @param tokenRelStore the token relation store
     * @param accountStore the account store
     * @param autoCreationTest the auto-creation test
     */
    private void revalidateAssessmentResult(
            final AssessmentResult result,
            final ReadableTokenRelationStore tokenRelStore,
            final ReadableAccountStore accountStore,
            @NonNull final Predicate<AccountID> autoCreationTest) {
        result.getHbarAdjustments().forEach((k, v) -> {
            if (v < 0) {
                // (FUTURE) This is here for mono-service fidelity, which refused to let an
                // auto-created account pay a custom fee, even if technically it is
                // receiving sufficient credits in the same transaction; consider removing.
                validateFalse(autoCreationTest.test(k), INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE);
            }
        });
        for (final var entry : result.getHtsAdjustments().entrySet()) {
            final var entryValue = entry.getValue();
            for (final var entryTx : entryValue.entrySet()) {
                final Long htsBalanceChange = entryTx.getValue();
                final var accountId = entryTx.getKey();
                final var tokenRel = tokenRelStore.get(accountId, entry.getKey());
                // revalidate collector's association
                if (htsBalanceChange > 0) {
                    if (tokenRel == null) {
                        final var currentAccount = accountStore.getAccountById(accountId);
                        final var mayBeAutoAssociatedHere = currentAccount != null
                                && (currentAccount.maxAutoAssociations() > currentAccount.usedAutoAssociations()
                                        || currentAccount.maxAutoAssociations() == -1);
                        validateTrue(mayBeAutoAssociatedHere, TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR);
                    }
                }

                if (htsBalanceChange < 0) {
                    // IMPORTANT: These special cases exist only to simulate mono-service failure codes in
                    // some of the "classic" custom fee scenarios encoded in EETs; but they have no logical
                    // priority relative to other failure responses that would be assigned in a later step
                    // if we didn't fail here
                    final var precedingChanges =
                            result.getImmutableInputTokenAdjustments().get(entry.getKey());
                    final var precedingAdjustment =
                            precedingChanges == null ? 0 : precedingChanges.getOrDefault(accountId, 0L);
                    final long precedingCredit = Math.max(0L, precedingAdjustment);
                    if (tokenRel == null) {
                        if (autoCreationTest.test(accountId)) {
                            // mono-service refuses to let an auto-created account pay a custom fee, even
                            // if technically it is receiving sufficient credits in the same transaction
                            throw new HandleException(INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE);
                        } else {
                            final var currentAccount = accountStore.getAccountById(accountId);
                            // We have to be careful not to fail when an NFT sender with auto-association slots
                            // is "paying" a royalty fee in the denomination of a fungible token that it is
                            // receiving in return for the NFT; but was not already associated with...see for
                            // example the royaltyCollectorsCanUseAutoAssociation() EET
                            final var mayBeAutoAssociatedHere = currentAccount != null
                                    && precedingCredit > 0
                                    && currentAccount.maxAutoAssociations() > currentAccount.usedAutoAssociations();
                            if (!mayBeAutoAssociatedHere) {
                                throw new HandleException(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Sets the initial NFT changes for the transaction. These are not going to change in the course of
     * assessing custom fees.
     *
     * @param op the transaction body
     */
    public void calculateAndSetInitialNftChanges(final CryptoTransferTransactionBody op) {
        initialNftChanges = numNftTransfers(op);
    }

    /**
     * Resets the initial NFT changes for the transaction.
     */
    public void resetInitialNftChanges() {
        initialNftChanges = 0;
    }
}
