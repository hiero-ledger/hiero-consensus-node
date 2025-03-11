// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN;
import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_IS_TREASURY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_STILL_OWNS_NFTS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_ID_REPEATED_IN_TOKEN_LIST;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_IS_PAUSED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES;
import static com.hedera.hapi.node.base.TokenType.NON_FUNGIBLE_UNIQUE;
import static com.hedera.node.app.hapi.fees.usage.SingletonEstimatorUtils.ESTIMATOR_UTILS;
import static com.hedera.node.app.hapi.fees.usage.crypto.CryptoOpsUsage.txnEstimateFactory;
import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.hasAccountNumOrAlias;
import static com.hedera.node.app.spi.fees.Fees.CONSTANT_FEE_DATA;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.hapi.node.token.TokenDissociateTransactionBody;
import com.hedera.node.app.hapi.fees.usage.SigUsage;
import com.hedera.node.app.hapi.fees.usage.token.TokenDissociateUsage;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableTokenRelationStore;
import com.hedera.node.app.service.token.impl.util.TokenHandlerHelper;
import com.hedera.node.app.service.token.impl.util.TokenRelListCalculator;
import com.hedera.node.app.service.token.impl.validators.TokenListChecks;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hederahashgraph.api.proto.java.FeeData;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link
 * HederaFunctionality#TOKEN_DISSOCIATE_FROM_ACCOUNT}.
 */
@Singleton
public class TokenDissociateFromAccountHandler implements TransactionHandler {
    // a sentinel value for headTokenId to indicate that no tokens are associated with the account
    private static final TokenID NO_ASSOCIATED_TOKENS =
            TokenID.newBuilder().tokenNum(-1).build();

    /**
     * Default constructor for injection.
     */
    @Inject
    public TokenDissociateFromAccountHandler() {
        // exists for injection
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var trxBody = context.body();
        final var op = trxBody.tokenDissociateOrThrow();

        final var target = op.accountOrElse(AccountID.DEFAULT);

        context.requireKeyOrThrow(target, INVALID_ACCOUNT_ID);
    }

    /**
     * This method is called during the handle workflow. It executes the actual transaction.
     *
     * <p>
     * Dissociates a token with an account, preventing various token operations from happening with
     * respect to the given account
     *
     * <p>
     * <b>Note:</b> this method will NOT apply account operation changes by account ID
     *
     * @throws NullPointerException if one of the arguments is {@code null}
     */
    public void handle(@NonNull final HandleContext context) {
        requireNonNull(context);
        final var storeFactory = context.storeFactory();
        final var accountStore = storeFactory.writableStore(WritableAccountStore.class);
        final var tokenStore = storeFactory.readableStore(ReadableTokenStore.class);
        final var tokenRelStore = storeFactory.writableStore(WritableTokenRelationStore.class);
        final var expiryValidator = context.expiryValidator();
        final var txn = context.body();
        final var op = txn.tokenDissociateOrThrow();
        final var tokenIds = op.tokens();
        final var validated = validateSemantics(
                op.accountOrThrow(), tokenIds, accountStore, tokenStore, tokenRelStore, expiryValidator);

        // Update the account and relevant token relations
        // Note: since we can't query an Account copyBuilder for current values, and since the account object is
        // undergoing an update (i.e. we can't accurately query account.numXYZs() at any arbitrary point during the
        // following loop), we have to keep track of the aggregate number of NFTs, auto associations, associations, and
        // positive balances to remove from the account separate from the account object itself. Confusing, but we'll
        // _add_ the number to subtract to each aggregating variable. The total subtraction for each variable will be
        // done outside the dissociation loop
        var numNftsToSubtract = 0L;
        var numAutoAssociationsToSubtract = 0;
        var numAssociationsToSubtract = 0;
        var numPositiveBalancesToSubtract = 0;
        final var account = validated.account();
        final var tokenRelsToRemove = new ArrayList<TokenRelation>();
        final var treasuryBalancesToUpdate = new ArrayList<TokenRelation>();
        for (final var dissociation : validated.dissociations()) {
            final var tokenRel = dissociation.tokenRel();
            final var tokenRelBalance = tokenRel.balance();
            final var token = dissociation.token();

            // Handle dissociation from an inactive (deleted or removed) token
            if (token == null || token.deleted()) {
                // Nothing to do here for a fungible token, downstream code already
                // "burns" our held units
                if (token != null && token.tokenType() == NON_FUNGIBLE_UNIQUE) {
                    // Downstream code already takes care of decrementing the number of
                    // positive balances in the case we owned serial numbers of this type
                    numNftsToSubtract += tokenRelBalance;
                }
            } else {
                // Handle active tokens
                validateFalse(
                        token.treasuryAccountId() != null
                                && token.treasuryAccountId().equals(tokenRel.accountId()),
                        ACCOUNT_IS_TREASURY);
                validateFalse(tokenRel.frozen(), ACCOUNT_FROZEN_FOR_TOKEN);

                if (tokenRelBalance > 0) {
                    if (token.tokenType() == NON_FUNGIBLE_UNIQUE) {
                        throw new HandleException(ACCOUNT_STILL_OWNS_NFTS);
                    } else {
                        throw new HandleException(TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES);
                    }
                }
            }

            // Regardless of the token status, now the token relation with the given account ID must be updated to zero
            final var updatedBalanceTokenRel =
                    tokenRel.copyBuilder().balance(0L).build();
            tokenRelsToRemove.add(updatedBalanceTokenRel);

            if (tokenRel.automaticAssociation()) {
                numAutoAssociationsToSubtract++;
            }

            if (tokenRel.balance() != 0) {
                numPositiveBalancesToSubtract++;
            }

            numAssociationsToSubtract++;
        }

        // get changes to account and token relations
        final var updatedTokenRels =
                new TokenRelListCalculator(tokenRelStore).removeTokenRels(account, tokenRelsToRemove);
        final var newHeadTokenId = updatedTokenRels.updatedHeadTokenId();

        // Update the account with the aggregate number of NFTs, auto associations, associations, and positive balances
        // to remove, as well as the new head token number
        final var updatedAcct = account.copyBuilder()
                .numberOwnedNfts(account.numberOwnedNfts() - numNftsToSubtract)
                .usedAutoAssociations(account.usedAutoAssociations() - numAutoAssociationsToSubtract)
                .numberAssociations(account.numberAssociations() - numAssociationsToSubtract)
                .numberPositiveBalances(account.numberPositiveBalances() - numPositiveBalancesToSubtract)
                .headTokenId(newHeadTokenId == null ? NO_ASSOCIATED_TOKENS : newHeadTokenId)
                .build();

        // Finally, update the account and the token relations via their respective stores
        accountStore.put(updatedAcct);
        updatedTokenRels.updatedTokenRelsStillInChain().forEach(tokenRelStore::put);
        tokenRelsToRemove.forEach(tokenRelStore::remove);
        treasuryBalancesToUpdate.forEach(tokenRelStore::put);
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var txn = context.body();
        final TokenDissociateTransactionBody op = txn.tokenDissociateOrThrow();

        validateTruePreCheck(hasAccountNumOrAlias(op.account()), INVALID_ACCOUNT_ID);
        validateFalsePreCheck(op.tokens().contains(TokenID.DEFAULT), INVALID_TOKEN_ID);

        validateTruePreCheck(!TokenListChecks.repeatsItself(op.tokens()), TOKEN_ID_REPEATED_IN_TOKEN_LIST);
    }

    /**
     * Performs checks that require state and context.
     */
    @NonNull
    private ValidatedResult validateSemantics(
            @NonNull final AccountID accountId,
            @NonNull final List<TokenID> tokenIds,
            @NonNull final WritableAccountStore accountStore,
            @NonNull final ReadableTokenStore tokenStore,
            @NonNull final WritableTokenRelationStore tokenRelStore,
            @NonNull final ExpiryValidator expiryValidator) {
        // Check that the account is usable
        final var acct = TokenHandlerHelper.getIfUsable(accountId, accountStore, expiryValidator, INVALID_ACCOUNT_ID);

        // Construct the dissociation for each token ID
        final var dissociations = new ArrayList<Dissociation>();
        for (final var tokenId : tokenIds) {
            final var tokenRel = tokenRelStore.get(accountId, tokenId);
            validateTrue(tokenRel != null, TOKEN_NOT_ASSOCIATED_TO_ACCOUNT);

            // Here we check/retrieve a token that may not be "usable," but since we are dissociating token relations,
            // we don't require a usable token (or even an existing token). We only need to update the token relation
            validateTrue(tokenId.tokenNum() > 0, INVALID_TOKEN_ID);
            final var possiblyUnusableToken = tokenStore.get(tokenId);
            final TokenRelation dissociatedTokenTreasuryRel;
            if (possiblyUnusableToken != null) {
                validateFalse(possiblyUnusableToken.paused(), TOKEN_IS_PAUSED);
                // If there is no treasury, or the token is deleted, we don't return
                // the dissociated balance to the treasury
                if (!possiblyUnusableToken.deleted() && possiblyUnusableToken.treasuryAccountId() != null) {
                    final var tokenTreasuryAcct = possiblyUnusableToken.treasuryAccountId();
                    dissociatedTokenTreasuryRel = tokenRelStore.get(tokenTreasuryAcct, tokenId);
                } else {
                    dissociatedTokenTreasuryRel = null;
                }
            } else {
                // If the token isn't found, assume the treasury token rel is null
                dissociatedTokenTreasuryRel = null;
            }

            final Dissociation dissociation =
                    new Dissociation(possiblyUnusableToken, tokenRel, acct, dissociatedTokenTreasuryRel);
            dissociations.add(dissociation);
        }

        return new ValidatedResult(acct, dissociations);
    }

    private record ValidatedResult(@NonNull Account account, @NonNull List<Dissociation> dissociations) {}

    private record Dissociation(
            @Nullable Token token,
            // This is the relation of the submitted account ID and the token ID
            @NonNull TokenRelation tokenRel,
            @NonNull Account acct,
            // This is the relation of the token's treasury account ID and the token ID
            @Nullable TokenRelation treasuryTokenRel) {}

    @NonNull
    @Override
    public Fees calculateFees(@NonNull final FeeContext feeContext) {
        requireNonNull(feeContext);
        final var body = feeContext.body();
        final var op = body.tokenDissociateOrThrow();
        final var accountId = op.accountOrThrow();
        final var readableAccountStore = feeContext.readableStore(ReadableAccountStore.class);
        final var account = readableAccountStore.getAccountById(accountId);

        return feeContext
                .feeCalculatorFactory()
                .feeCalculator(SubType.DEFAULT)
                .legacyCalculate(sigValueObj -> usageGiven(CommonPbjConverters.fromPbj(body), sigValueObj, account));
    }

    public FeeData usageGiven(
            final com.hederahashgraph.api.proto.java.TransactionBody txn,
            final SigValueObj svo,
            final Account account) {
        if (account == null) {
            return CONSTANT_FEE_DATA;
        } else {
            final var sigUsage =
                    new SigUsage(svo.getTotalSigCount(), svo.getSignatureSize(), svo.getPayerAcctSigCount());
            final var estimate =
                    TokenDissociateUsage.newEstimate(txn, txnEstimateFactory.get(sigUsage, txn, ESTIMATOR_UTILS));
            return estimate.get();
        }
    }
}
