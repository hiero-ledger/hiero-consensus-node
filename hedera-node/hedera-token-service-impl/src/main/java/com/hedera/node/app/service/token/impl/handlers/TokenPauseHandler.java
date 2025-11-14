// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_HAS_NO_PAUSE_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_WAS_DELETED;
import static com.hedera.node.app.hapi.fees.usage.token.TokenOpsUsageUtils.TOKEN_OPS_USAGE_UTILS;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.WritableTokenStore;
import com.hedera.node.app.service.token.records.TokenBaseStreamBuilder;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link
 * HederaFunctionality#TOKEN_PAUSE}.
 */
@Singleton
public class TokenPauseHandler implements TransactionHandler {
    /**
     * Default constructor for injection.
     */
    @Inject
    public TokenPauseHandler() {
        // Exists for injection
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().tokenPause();
        final var tokenStore = context.createStore(ReadableTokenStore.class);
        final var tokenMeta = tokenStore.getTokenMeta(op.tokenOrElse(TokenID.DEFAULT));
        if (tokenMeta == null) {
            throw new PreCheckException(INVALID_TOKEN_ID);
        }
        if (tokenMeta.hasPauseKey()) {
            context.requireKey(tokenMeta.pauseKey());
        }
    }

    /**
     * This method is called during the handle workflow. It executes the actual transaction.
     *
     * @param context the {@link HandleContext} for the active transaction
     * @throws NullPointerException if one of the arguments is {@code null}
     */
    @Override
    public void handle(@NonNull final HandleContext context) {
        requireNonNull(context);

        final var op = context.body().tokenPause();
        final var tokenStore = context.storeFactory().writableStore(WritableTokenStore.class);
        var token = tokenStore.get(op.tokenOrElse(TokenID.DEFAULT));
        validateTrue(token != null, INVALID_TOKEN_ID);
        validateTrue(token.hasPauseKey(), TOKEN_HAS_NO_PAUSE_KEY);
        validateFalse(token.deleted(), TOKEN_WAS_DELETED);

        final var copyBuilder = token.copyBuilder();
        copyBuilder.paused(true);
        tokenStore.put(copyBuilder.build());
        final var recordBuilder = context.savepointStack().getBaseBuilder(TokenBaseStreamBuilder.class);
        recordBuilder.tokenType(token.tokenType());
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var txn = context.body();
        final var op = txn.tokenPauseOrThrow();
        if (!op.hasToken()) {
            throw new PreCheckException(INVALID_TOKEN_ID);
        }
    }

    @NonNull
    @Override
    public Fees calculateFees(@NonNull final FeeContext feeContext) {
        final var meta = TOKEN_OPS_USAGE_UTILS.tokenPauseUsageFrom();
        return feeContext
                .feeCalculatorFactory()
                .feeCalculator(SubType.DEFAULT)
                .addBytesPerTransaction(meta.getBpt())
                .calculate();
    }
}
