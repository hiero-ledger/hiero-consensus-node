// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKENS_PER_ACCOUNT_LIMIT_EXCEEDED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_ID_REPEATED_IN_TOKEN_LIST;
import static com.hedera.hapi.node.base.SubType.DEFAULT;
import static com.hedera.node.app.hapi.fees.usage.SingletonEstimatorUtils.ESTIMATOR_UTILS;
import static com.hedera.node.app.service.token.impl.comparator.TokenComparators.TOKEN_ID_COMPARATOR;
import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.hasAccountNumOrAlias;
import static com.hedera.node.app.service.token.impl.util.TokenHandlerHelper.getIfUsable;
import static com.hedera.node.app.spi.fees.Fees.CONSTANT_FEE_DATA;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.node.app.hapi.fees.usage.SigUsage;
import com.hedera.node.app.hapi.fees.usage.TxnUsageEstimator;
import com.hedera.node.app.hapi.fees.usage.token.TokenAssociateUsage;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableTokenRelationStore;
import com.hedera.node.app.service.token.impl.util.TokenHandlerHelper;
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
import com.hedera.node.config.data.EntitiesConfig;
import com.hedera.node.config.data.TokensConfig;
import com.hederahashgraph.api.proto.java.FeeData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link
 * HederaFunctionality#TOKEN_ASSOCIATE_TO_ACCOUNT}.
 */
@Singleton
public class TokenAssociateToAccountHandler extends BaseTokenHandler implements TransactionHandler {
    /**
     * Default constructor for injection.
     */
    @Inject
    public TokenAssociateToAccountHandler() {
        // Exists for injection
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().tokenAssociateOrThrow();

        final var target = op.accountOrElse(AccountID.DEFAULT);
        context.requireKeyOrThrow(target, INVALID_ACCOUNT_ID);
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        requireNonNull(context);
        final var storeFactory = context.storeFactory();
        final var tokenStore = requireNonNull(storeFactory.readableStore(ReadableTokenStore.class));
        final var op = context.body().tokenAssociateOrThrow();
        final var tokenIds = op.tokens().stream().sorted(TOKEN_ID_COMPARATOR).toList();
        final var tokensConfig = context.configuration().getConfigData(TokensConfig.class);
        final var entitiesConfig = context.configuration().getConfigData(EntitiesConfig.class);
        final var accountStore = storeFactory.writableStore(WritableAccountStore.class);
        final var tokenRelStore = storeFactory.writableStore(WritableTokenRelationStore.class);
        final var validated = validateSemantics(
                tokenIds,
                op.accountOrThrow(),
                tokensConfig,
                entitiesConfig,
                accountStore,
                tokenStore,
                tokenRelStore,
                context.expiryValidator());

        // Now that we've validated we can link all the new token IDs to the account,
        // create the corresponding token relations and update the account
        createAndLinkTokenRels(validated.account(), validated.tokens(), accountStore, tokenRelStore);
    }

    /**
     * Performs checks independent of state or context.
     */
    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var txn = context.body();
        final var op = txn.tokenAssociateOrThrow();

        validateTruePreCheck(hasAccountNumOrAlias(op.account()), INVALID_ACCOUNT_ID);
        validateFalsePreCheck(op.tokens().contains(TokenID.DEFAULT), INVALID_TOKEN_ID);

        validateFalsePreCheck(TokenListChecks.repeatsItself(op.tokens()), TOKEN_ID_REPEATED_IN_TOKEN_LIST);
    }

    /**
     * Performs checks that require state and context.
     */
    @NonNull
    private Validated validateSemantics(
            @NonNull final List<TokenID> tokenIds,
            @NonNull final AccountID accountId,
            @NonNull final TokensConfig tokenConfig,
            @NonNull final EntitiesConfig entitiesConfig,
            @NonNull final WritableAccountStore accountStore,
            @NonNull final ReadableTokenStore tokenStore,
            @NonNull final WritableTokenRelationStore tokenRelStore,
            @NonNull final ExpiryValidator expiryValidator) {
        requireNonNull(tokenConfig);
        requireNonNull(entitiesConfig);

        // Check that the system hasn't reached its limit of token associations
        validateTrue(
                isTotalNumTokenRelsWithinMax(tokenIds.size(), tokenRelStore, tokenConfig.maxAggregateRels()),
                MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED);

        // Check that the account is usable
        final var account =
                TokenHandlerHelper.getIfUsable(accountId, accountStore, expiryValidator, INVALID_ACCOUNT_ID);

        // Check that the given tokens exist and are usable
        final var tokens = new ArrayList<Token>();
        for (final TokenID tokenId : tokenIds) {
            final var token = getIfUsable(tokenId, tokenStore);
            tokens.add(token);
        }

        // Check that the total number of old and new token IDs wouldn't be bigger than
        // the max number of token associations allowed per account (if the rel limit is enabled)
        validateTrue(
                maxAccountAssociationsAllowTokenRels(tokenConfig, entitiesConfig, account, tokenIds),
                TOKENS_PER_ACCOUNT_LIMIT_EXCEEDED);

        // Check that a token rel doesn't already exist for each new token ID
        for (final TokenID tokenId : tokenIds) {
            final var existingTokenRel = tokenRelStore.get(accountId, tokenId);
            validateTrue(existingTokenRel == null, TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT);
        }

        return new Validated(account, tokens);
    }

    private boolean isTotalNumTokenRelsWithinMax(
            final int numNewTokenRels, WritableTokenRelationStore tokenRelStore, long maxNumTokenRels) {
        return tokenRelStore.sizeOfState() + numNewTokenRels <= maxNumTokenRels;
    }

    /**
     * Method that checks if the number of token associations for the given account is within the
     * allowable limit set by the config (if the limit is enabled).
     *
     * @return true if tokenAssociationsLimited is false or if the number of token associations is
     * within the allowed maxTokensPerAccount
     */
    private boolean maxAccountAssociationsAllowTokenRels(
            @NonNull final TokensConfig config,
            @NonNull final EntitiesConfig entitiesConfig,
            @NonNull final Account account,
            @NonNull final List<TokenID> tokenIds) {
        final var numAssociations = requireNonNull(account).numberAssociations();
        final var tokenAssociationsLimited = entitiesConfig.limitTokenAssociations();
        final var maxTokensPerAccount = config.maxPerAccount();
        return !tokenAssociationsLimited || (numAssociations + tokenIds.size() <= maxTokensPerAccount);
    }

    private record Validated(@NonNull Account account, @NonNull List<Token> tokens) {}

    @NonNull
    @Override
    public Fees calculateFees(@NonNull final FeeContext feeContext) {
        requireNonNull(feeContext);
        final var body = feeContext.body();
        final var op = body.tokenAssociateOrThrow();

        final var calculator = feeContext.feeCalculatorFactory().feeCalculator(DEFAULT);
        final var unlimitedAssociationsEnabled =
                feeContext.configuration().getConfigData(EntitiesConfig.class).unlimitedAutoAssociationsEnabled();

        // If the unlimited auto-associations feature is enabled, we calculate the fees in a new way, because the
        // association price is changed to $0.05. When the feature is enabled the feeSchedules.json will be updated
        // to reflect the price change and the else case will be removed.
        // Until then, we calculate the fees using the legacy method.
        // NOTE: If this flag is disabled, the feeSchedules.json should be modified as well
        if (unlimitedAssociationsEnabled) {
            calculator.resetUsage();
            calculator.addVerificationsPerTransaction(Math.max(0, feeContext.numTxnSignatures() - 1));
            calculator.addBytesPerTransaction(op.tokens().size());
            return calculator.calculate();
        } else {
            final var accountId = op.accountOrThrow();
            final var readableAccountStore = feeContext.readableStore(ReadableAccountStore.class);
            final var account = readableAccountStore.getAccountById(accountId);
            return feeContext
                    .feeCalculatorFactory()
                    .feeCalculator(DEFAULT)
                    .legacyCalculate(
                            sigValueObj -> usageGiven(CommonPbjConverters.fromPbj(body), sigValueObj, account));
        }
    }

    private FeeData usageGiven(
            final com.hederahashgraph.api.proto.java.TransactionBody txn,
            final SigValueObj svo,
            final Account account) {
        if (account == null) {
            return CONSTANT_FEE_DATA;
        } else {
            final var sigUsage =
                    new SigUsage(svo.getTotalSigCount(), svo.getSignatureSize(), svo.getPayerAcctSigCount());
            final var estimate = new TokenAssociateUsage(txn, new TxnUsageEstimator(sigUsage, txn, ESTIMATOR_UTILS));
            return estimate.givenCurrentExpiry(account.expirationSecond()).get();
        }
    }
}
