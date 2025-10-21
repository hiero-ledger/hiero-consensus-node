// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.dispatcher;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj;
import static com.hedera.node.app.hapi.utils.CommonUtils.productWouldOverflow;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.utils.fee.FeeBuilder;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.app.spi.workflows.WarmupContext;
import com.hedera.node.config.data.FeesConfig;
import com.hederahashgraph.api.proto.java.ExchangeRate;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.hapi.fees.FeeResult;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * A {@code TransactionDispatcher} provides functionality to forward pre-check, pre-handle, and handle-transaction
 * requests to the appropriate handler
 *
 * <p>For handle, mostly just supports the limited form of the Consensus Service handlers described in
 * <a href="https://github.com/hashgraph/hedera-services/issues/4945">issue #4945</a>,
 * while still trying to make a bit of progress toward the general implementation.
 */
@Singleton
public class TransactionDispatcher {
    public static final String TYPE_NOT_SUPPORTED = "This transaction type is not supported";
    public static final String SYSTEM_DELETE_WITHOUT_ID_CASE = "SystemDelete without IdCase";
    public static final String SYSTEM_UNDELETE_WITHOUT_ID_CASE = "SystemUndelete without IdCase";

    protected final TransactionHandlers handlers;

    /**
     * Creates a {@code TransactionDispatcher}.
     *
     * @param handlers the handlers for all transaction types
     */
    @Inject
    public TransactionDispatcher(@NonNull final TransactionHandlers handlers) {
        this.handlers = requireNonNull(handlers);
    }

    /**
     * Dispatch a {@code pureChecks()} request. It is forwarded to the correct handler, which takes care of the specific
     * functionality
     *
     * @param context the {@link PureChecksContext} to be validated
     * @throws NullPointerException if {@code context} is {@code null}
     */
    public void dispatchPureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context, "The supplied argument 'context' cannot be null!");
        try {
            final var handler = getHandler(context.body());
            handler.pureChecks(context);
        } catch (UnsupportedOperationException ex) {
            throw new PreCheckException(ResponseCodeEnum.INVALID_TRANSACTION_BODY);
        }
    }

    /**
     * Dispatch a pre-handle request. It is forwarded to the correct handler, which takes care of the specific
     * functionality
     *
     * @param context the context of the pre-handle workflow
     * @throws NullPointerException if {@code context} is {@code null}
     */
    public void dispatchPreHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context, "The supplied argument 'context' cannot be null!");

        try {
            final var handler = getHandler(context.body());
            handler.preHandle(context);
        } catch (UnsupportedOperationException ex) {
            throw new PreCheckException(ResponseCodeEnum.INVALID_TRANSACTION_BODY);
        }
    }

    /**
     * Dispatch a warmup request. It is forwarded to the correct handler, which takes care of the specific
     * functionality
     *
     * @param context the context of the warmup workflow
     * @throws NullPointerException if {@code context} is {@code null}
     */
    public void dispatchWarmup(@NonNull final WarmupContext context) {
        requireNonNull(context, "The supplied argument 'context' cannot be null!");

        try {
            final var handler = getHandler(context.body());
            handler.warm(context);
        } catch (UnsupportedOperationException ex) {
            // do nothing, the handler should have been used before we reach this point
        }
    }

    /**
     * Dispatch a compute fees request. It is forwarded to the correct handler, which takes care of the specific
     * calculation and returns the resulting {@link Fees}.
     *
     * @param feeContext information needed to calculate the fees
     * @return the calculated fees
     */
    @NonNull
    public Fees dispatchComputeFees(@NonNull final FeeContext feeContext) {
        requireNonNull(feeContext, "feeContext must not be null!");

        try {
            final var handler = getHandler(feeContext.body());
            if (shouldUseSimpleFees(feeContext)) {
                var feeResult = handler.calculateFeeResult(feeContext);
                return feeResultToFees(feeResult, fromPbj(feeContext.activeRate()));
            }
            return handler.calculateFees(feeContext);
        } catch (UnsupportedOperationException ex) {
            throw new HandleException(ResponseCodeEnum.INVALID_TRANSACTION_BODY);
        }
    }

    private boolean shouldUseSimpleFees(FeeContext feeContext) {
        if (!feeContext.configuration().getConfigData(FeesConfig.class).simpleFeesEnabled()) {
            return false;
        }

        return switch (feeContext.body().data().kind()) {
            case CONSENSUS_CREATE_TOPIC, CONSENSUS_SUBMIT_MESSAGE, CONSENSUS_UPDATE_TOPIC, CONSENSUS_DELETE_TOPIC -> true;
            default -> false;
        };
    }

    private static long tinycentsToTinybars(final long amount, final ExchangeRate rate) {
        final var hbarEquiv = rate.getHbarEquiv();
        if (productWouldOverflow(amount, hbarEquiv)) {
            return FeeBuilder.getTinybarsFromTinyCents(rate, amount);
        }
        return amount * hbarEquiv / rate.getCentEquiv();
    }

    private static Fees feeResultToFees(FeeResult feeResult, ExchangeRate rate) {
        return new Fees(
                tinycentsToTinybars(feeResult.node, rate),
                tinycentsToTinybars(feeResult.network, rate),
                tinycentsToTinybars(feeResult.service, rate));
    }

    /**
     * Dispatch a handle request. It is forwarded to the correct handler, which takes care of the specific
     * functionality
     *
     * @param context the {@link HandleContext} with all the information needed to handle the transaction
     * @throws NullPointerException if {@code context} is {@code null}
     */
    public void dispatchHandle(@NonNull final HandleContext context) throws HandleException {
        requireNonNull(context, "The supplied argument 'context' cannot be null!");

        try {
            final var handler = getHandler(context.body());
            handler.handle(context);
        } catch (UnsupportedOperationException ex) {
            throw new HandleException(ResponseCodeEnum.INVALID_TRANSACTION_BODY);
        }
    }

    @NonNull
    private TransactionHandler getHandler(@NonNull final TransactionBody txBody) {
        return switch (txBody.data().kind()) {
            case CONSENSUS_CREATE_TOPIC -> handlers.consensusCreateTopicHandler();
            case CONSENSUS_UPDATE_TOPIC -> handlers.consensusUpdateTopicHandler();
            case CONSENSUS_DELETE_TOPIC -> handlers.consensusDeleteTopicHandler();
            case CONSENSUS_SUBMIT_MESSAGE -> handlers.consensusSubmitMessageHandler();

            case CONTRACT_CREATE_INSTANCE -> handlers.contractCreateHandler();
            case CONTRACT_UPDATE_INSTANCE -> handlers.contractUpdateHandler();
            case CONTRACT_CALL -> handlers.contractCallHandler();
            case CONTRACT_DELETE_INSTANCE -> handlers.contractDeleteHandler();
            case ETHEREUM_TRANSACTION -> handlers.ethereumTransactionHandler();
            case LAMBDA_SSTORE -> handlers.lambdaSStoreHandler();
            case HOOK_DISPATCH -> handlers.hookDispatchHandler();

            case CRYPTO_CREATE_ACCOUNT -> handlers.cryptoCreateHandler();
            case CRYPTO_UPDATE_ACCOUNT -> handlers.cryptoUpdateHandler();
            case CRYPTO_TRANSFER -> handlers.cryptoTransferHandler();
            case CRYPTO_DELETE -> handlers.cryptoDeleteHandler();
            case CRYPTO_APPROVE_ALLOWANCE -> handlers.cryptoApproveAllowanceHandler();
            case CRYPTO_DELETE_ALLOWANCE -> handlers.cryptoDeleteAllowanceHandler();
            case CRYPTO_ADD_LIVE_HASH -> handlers.cryptoAddLiveHashHandler();
            case CRYPTO_DELETE_LIVE_HASH -> handlers.cryptoDeleteLiveHashHandler();

            case FILE_CREATE -> handlers.fileCreateHandler();
            case FILE_UPDATE -> handlers.fileUpdateHandler();
            case FILE_DELETE -> handlers.fileDeleteHandler();
            case FILE_APPEND -> handlers.fileAppendHandler();

            case FREEZE -> handlers.freezeHandler();

            case NODE_CREATE -> handlers.nodeCreateHandler();
            case NODE_DELETE -> handlers.nodeDeleteHandler();
            case NODE_UPDATE -> handlers.nodeUpdateHandler();

            case UNCHECKED_SUBMIT -> handlers.networkUncheckedSubmitHandler();

            case SCHEDULE_CREATE -> handlers.scheduleCreateHandler();
            case SCHEDULE_SIGN -> handlers.scheduleSignHandler();
            case SCHEDULE_DELETE -> handlers.scheduleDeleteHandler();

            case TOKEN_CREATION -> handlers.tokenCreateHandler();
            case TOKEN_UPDATE -> handlers.tokenUpdateHandler();
            case TOKEN_MINT -> handlers.tokenMintHandler();
            case TOKEN_BURN -> handlers.tokenBurnHandler();
            case TOKEN_DELETION -> handlers.tokenDeleteHandler();
            case TOKEN_WIPE -> handlers.tokenAccountWipeHandler();
            case TOKEN_FREEZE -> handlers.tokenFreezeAccountHandler();
            case TOKEN_UNFREEZE -> handlers.tokenUnfreezeAccountHandler();
            case TOKEN_GRANT_KYC -> handlers.tokenGrantKycToAccountHandler();
            case TOKEN_REVOKE_KYC -> handlers.tokenRevokeKycFromAccountHandler();
            case TOKEN_ASSOCIATE -> handlers.tokenAssociateToAccountHandler();
            case TOKEN_DISSOCIATE -> handlers.tokenDissociateFromAccountHandler();
            case TOKEN_FEE_SCHEDULE_UPDATE -> handlers.tokenFeeScheduleUpdateHandler();
            case TOKEN_PAUSE -> handlers.tokenPauseHandler();
            case TOKEN_UNPAUSE -> handlers.tokenUnpauseHandler();
            case TOKEN_UPDATE_NFTS -> handlers.tokenUpdateNftsHandler();
            case TOKEN_REJECT -> handlers.tokenRejectHandler();
            case TOKEN_CLAIM_AIRDROP -> handlers.tokenClaimAirdropHandler();
            case TOKEN_AIRDROP -> handlers.tokenAirdropHandler();
            case TOKEN_CANCEL_AIRDROP -> handlers.tokenCancelAirdropHandler();

            case UTIL_PRNG -> handlers.utilPrngHandler();
            case ATOMIC_BATCH -> handlers.atomicBatchHandler();

            case HISTORY_PROOF_KEY_PUBLICATION -> handlers.historyProofKeyPublicationHandler();
            case HISTORY_PROOF_SIGNATURE -> handlers.historyProofSignatureHandler();
            case HISTORY_PROOF_VOTE -> handlers.historyProofVoteHandler();

            case HINTS_KEY_PUBLICATION -> handlers.hintsKeyPublicationHandler();
            case HINTS_PARTIAL_SIGNATURE -> handlers.hintsPartialSignatureHandler();
            case HINTS_PREPROCESSING_VOTE -> handlers.hintsPreprocessingVoteHandler();
            case CRS_PUBLICATION -> handlers.crsPublicationHandler();

            case SYSTEM_DELETE ->
                switch (txBody.systemDeleteOrThrow().id().kind()) {
                    case CONTRACT_ID -> handlers.contractSystemDeleteHandler();
                    case FILE_ID -> handlers.fileSystemDeleteHandler();
                    default -> throw new UnsupportedOperationException(SYSTEM_DELETE_WITHOUT_ID_CASE);
                };
            case SYSTEM_UNDELETE ->
                switch (txBody.systemUndeleteOrThrow().id().kind()) {
                    case CONTRACT_ID -> handlers.contractSystemUndeleteHandler();
                    case FILE_ID -> handlers.fileSystemUndeleteHandler();
                    default -> throw new UnsupportedOperationException(SYSTEM_UNDELETE_WITHOUT_ID_CASE);
                };

            default -> throw new UnsupportedOperationException(TYPE_NOT_SUPPORTED);
        };
    }
}
