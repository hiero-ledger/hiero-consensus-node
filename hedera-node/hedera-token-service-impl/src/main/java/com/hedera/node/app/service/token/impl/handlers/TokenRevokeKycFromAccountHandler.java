// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_HAS_NO_KYC_KEY;
import static com.hedera.node.app.hapi.fees.usage.SingletonEstimatorUtils.ESTIMATOR_UTILS;
import static com.hedera.node.app.hapi.fees.usage.crypto.CryptoOpsUsage.txnEstimateFactory;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.node.app.hapi.fees.usage.SigUsage;
import com.hedera.node.app.hapi.fees.usage.token.TokenRevokeKycUsage;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.WritableTokenRelationStore;
import com.hedera.node.app.service.token.impl.util.TokenHandlerHelper;
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
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link
 * HederaFunctionality#TOKEN_REVOKE_KYC_FROM_ACCOUNT}.
 */
@Singleton
public class TokenRevokeKycFromAccountHandler implements TransactionHandler {
    /**
     * Default constructor for injection.
     */
    @Inject
    public TokenRevokeKycFromAccountHandler() {
        // No-op
    }

    /**
     * This method is called during the pre-handle workflow.
     *
     * @param context the {@link PreHandleContext} which collects all information
     * @throws PreCheckException    for invalid tokens or if the token has no KYC key
     * @throws NullPointerException if one of the arguments is {@code null}
     */
    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);

        final var op = context.body().tokenRevokeKycOrThrow();
        final var tokenStore = context.createStore(ReadableTokenStore.class);
        final var tokenMeta = tokenStore.getTokenMeta(op.tokenOrElse(TokenID.DEFAULT));
        if (tokenMeta == null) throw new PreCheckException(INVALID_TOKEN_ID);
        if (tokenMeta.hasKycKey()) {
            context.requireKey(tokenMeta.kycKey());
        } else {
            throw new PreCheckException(TOKEN_HAS_NO_KYC_KEY);
        }
    }

    /**
     * This method is called during the handle workflow. It executes the actual transaction.
     *
     * @param handleContext the {@link HandleContext} of the transaction
     * @throws NullPointerException if one of the arguments is {@code null}
     */
    public void handle(@NonNull final HandleContext handleContext) {
        requireNonNull(handleContext);

        final var op = handleContext.body().tokenRevokeKycOrThrow();
        final var tokenId = op.tokenOrThrow();
        final var accountId = op.accountOrElse(AccountID.DEFAULT);
        final var storeFactory = handleContext.storeFactory();
        final var tokenRelStore = storeFactory.writableStore(WritableTokenRelationStore.class);
        final var accountStore = storeFactory.readableStore(ReadableAccountStore.class);
        final var expiryValidator = handleContext.expiryValidator();
        final var tokenStore = storeFactory.readableStore(ReadableTokenStore.class);
        final var tokenRel =
                validateSemantics(accountId, tokenId, tokenRelStore, accountStore, expiryValidator, tokenStore);

        final var tokenRelBuilder = tokenRel.copyBuilder();
        tokenRelBuilder.kycGranted(false);
        tokenRelStore.put(tokenRelBuilder.build());
    }

    /**
     * Performs checks independent of state or context.
     */
    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var txn = context.body();
        final var op = txn.tokenRevokeKycOrThrow();
        if (!op.hasToken()) {
            throw new PreCheckException(INVALID_TOKEN_ID);
        }

        if (!op.hasAccount()) {
            throw new PreCheckException(ResponseCodeEnum.INVALID_ACCOUNT_ID);
        }
    }

    /**
     * Performs checks that the entities related to this transaction exist and are valid.
     *
     * @return the token relation for the given token and account
     */
    @NonNull
    private TokenRelation validateSemantics(
            @NonNull final AccountID accountId,
            @NonNull final TokenID tokenId,
            @NonNull final WritableTokenRelationStore tokenRelStore,
            @NonNull final ReadableAccountStore accountStore,
            @NonNull final ExpiryValidator expiryValidator,
            @NonNull final ReadableTokenStore tokenStore)
            throws HandleException {
        // Throws if account is unusable
        TokenHandlerHelper.getIfUsable(accountId, accountStore, expiryValidator, INVALID_ACCOUNT_ID);
        // Throws if token is unusable
        TokenHandlerHelper.getIfUsable(tokenId, tokenStore);
        return TokenHandlerHelper.getIfUsable(accountId, tokenId, tokenRelStore);
    }

    @NonNull
    @Override
    public Fees calculateFees(@NonNull final FeeContext feeContext) {
        requireNonNull(feeContext);
        final var op = feeContext.body();

        return feeContext
                .feeCalculatorFactory()
                .feeCalculator(SubType.DEFAULT)
                .legacyCalculate(sigValueObj -> usageGiven(CommonPbjConverters.fromPbj(op), sigValueObj));
    }

    public FeeData usageGiven(final com.hederahashgraph.api.proto.java.TransactionBody txn, final SigValueObj svo) {
        final var sigUsage = new SigUsage(svo.getTotalSigCount(), svo.getSignatureSize(), svo.getPayerAcctSigCount());
        final var estimate =
                TokenRevokeKycUsage.newEstimate(txn, txnEstimateFactory.get(sigUsage, txn, ESTIMATOR_UTILS));
        return estimate.get();
    }
}
