// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_ID_DOES_NOT_EXIST;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSFER_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TRANSFER_ACCOUNT_SAME_AS_DELETE_ACCOUNT;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SubType;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.node.app.hapi.utils.fee.CryptoFeeBuilder;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.service.token.records.CryptoDeleteStreamBuilder;
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
 * HederaFunctionality#CRYPTO_DELETE}.
 */
@Singleton
public class CryptoDeleteHandler implements TransactionHandler {
    private final CryptoFeeBuilder usageEstimator = new CryptoFeeBuilder();

    /**
     * Default constructor for injection.
     */
    @Inject
    public CryptoDeleteHandler() {
        // Exists for injection
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var txn = context.body();
        final var op = txn.cryptoDeleteOrThrow();

        if (!op.hasDeleteAccountID() || !op.hasTransferAccountID()) {
            throw new PreCheckException(ACCOUNT_ID_DOES_NOT_EXIST);
        }

        if (op.deleteAccountIDOrThrow().equals(op.transferAccountIDOrThrow())) {
            throw new PreCheckException(TRANSFER_ACCOUNT_SAME_AS_DELETE_ACCOUNT);
        }
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().cryptoDeleteOrThrow();
        final var deleteAccountId = op.deleteAccountIDOrElse(AccountID.DEFAULT);
        final var transferAccountId = op.transferAccountIDOrElse(AccountID.DEFAULT);
        context.requireKeyOrThrow(deleteAccountId, INVALID_ACCOUNT_ID)
                .requireKeyIfReceiverSigRequired(transferAccountId, INVALID_TRANSFER_ACCOUNT_ID);
    }

    @Override
    public void handle(@NonNull final HandleContext context) {
        requireNonNull(context);
        final var op = context.body().cryptoDeleteOrThrow();
        context.storeFactory()
                .serviceApi(TokenServiceApi.class)
                .deleteAndTransfer(
                        op.deleteAccountIDOrThrow(),
                        op.transferAccountIDOrThrow(),
                        context.expiryValidator(),
                        context.savepointStack().getBaseBuilder(CryptoDeleteStreamBuilder.class));
    }

    @NonNull
    @Override
    public Fees calculateFees(@NonNull final FeeContext feeContext) {
        requireNonNull(feeContext);
        final var body = feeContext.body();
        return feeContext
                .feeCalculatorFactory()
                .feeCalculator(SubType.DEFAULT)
                .legacyCalculate(sigValueObj ->
                        usageEstimator.getCryptoDeleteTxFeeMatrices(CommonPbjConverters.fromPbj(body), sigValueObj));
    }
}
