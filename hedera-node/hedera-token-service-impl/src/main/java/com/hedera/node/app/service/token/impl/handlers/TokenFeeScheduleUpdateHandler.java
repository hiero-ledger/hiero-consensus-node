// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CUSTOM_FEES_LIST_TOO_LONG;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CUSTOM_SCHEDULE_ALREADY_HAS_NO_FEES;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CUSTOM_FEE_COLLECTOR;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_HAS_NO_FEE_SCHEDULE_KEY;
import static com.hedera.node.app.hapi.fees.usage.SingletonEstimatorUtils.ESTIMATOR_UTILS;
import static com.hedera.node.app.hapi.fees.usage.token.TokenOpsUsage.LONG_BASIC_ENTITY_ID_SIZE;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.token.TokenFeeScheduleUpdateTransactionBody;
import com.hedera.hapi.node.transaction.CustomFee;
import com.hedera.node.app.hapi.fees.usage.token.TokenOpsUsage;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.WritableTokenStore;
import com.hedera.node.app.service.token.impl.util.TokenHandlerHelper;
import com.hedera.node.app.service.token.impl.validators.CustomFeesValidator;
import com.hedera.node.app.service.token.records.TokenBaseStreamBuilder;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.config.data.TokensConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link
 * HederaFunctionality#TOKEN_FEE_SCHEDULE_UPDATE}.
 */
@Singleton
public class TokenFeeScheduleUpdateHandler implements TransactionHandler {
    private final CustomFeesValidator customFeesValidator;

    /**
     * Default constructor for injection.
     * @param customFeesValidator the custom fees validator
     */
    @Inject
    public TokenFeeScheduleUpdateHandler(@NonNull final CustomFeesValidator customFeesValidator) {
        requireNonNull(customFeesValidator);
        this.customFeesValidator = customFeesValidator;
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var txn = context.body();
        requireNonNull(txn);
        final var op = txn.tokenFeeScheduleUpdateOrThrow();
        if (!op.hasTokenId()) {
            throw new PreCheckException(INVALID_TOKEN_ID);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);

        final var op = context.body().tokenFeeScheduleUpdateOrThrow();
        final var tokenId = op.tokenIdOrElse(TokenID.DEFAULT);

        final var tokenStore = context.createStore(ReadableTokenStore.class);
        final var tokenMetadata = tokenStore.getTokenMeta(tokenId);
        if (tokenMetadata == null) throw new PreCheckException(INVALID_TOKEN_ID);
        if (tokenMetadata.hasFeeScheduleKey()) {
            context.requireKey(tokenMetadata.feeScheduleKey());
            for (final var customFee : op.customFees()) {
                final var collector = customFee.feeCollectorAccountIdOrElse(AccountID.DEFAULT);
                context.requireKeyIfReceiverSigRequired(collector, INVALID_CUSTOM_FEE_COLLECTOR);
            }
        }
        // we do not set a failure status if a fee schedule key is not present for the token,
        // we choose to fail with TOKEN_HAS_NO_FEE_SCHEDULE_KEY in the handle() method
    }

    /**
     * Handles a transaction with {@link HederaFunctionality#TOKEN_FEE_SCHEDULE_UPDATE}.
     * @param context the context of the transaction
     */
    @Override
    public void handle(@NonNull final HandleContext context) {
        requireNonNull(context);

        final var txn = context.body();

        // get the latest configuration
        final var config = context.configuration().getConfigData(TokensConfig.class);
        final var op = txn.tokenFeeScheduleUpdateOrThrow();

        // validate checks in handle
        final var storeFactory = context.storeFactory();
        final var tokenStore = storeFactory.writableStore(WritableTokenStore.class);
        final var token = validateSemantics(op, tokenStore, config);

        // create readable stores from the context
        final var readableAccountStore = storeFactory.readableStore(ReadableAccountStore.class);
        final var readableTokenRelsStore = storeFactory.readableStore(ReadableTokenRelationStore.class);

        if (token.customFees().isEmpty() && op.customFees().isEmpty()) {
            throw new HandleException(CUSTOM_SCHEDULE_ALREADY_HAS_NO_FEES);
        }
        // validate custom fees before committing
        customFeesValidator.validateForFeeScheduleUpdate(
                token,
                readableAccountStore,
                readableTokenRelsStore,
                tokenStore,
                op.customFees(),
                context.expiryValidator());
        // set the custom fees on token
        final var copy = token.copyBuilder().customFees(op.customFees());
        // add token to the modifications map
        tokenStore.put(copy.build());

        final var tokenBaseStreamBuilder = context.savepointStack().getBaseBuilder(TokenBaseStreamBuilder.class);
        tokenBaseStreamBuilder.tokenType(token.tokenType());
    }

    /**
     * Validate semantics of the transaction in handle call. This method is called before the
     * transaction is handled.
     * @param op the transaction body
     * @param tokenStore the token store
     * @param config the token service config
     * @return the token
     */
    private Token validateSemantics(
            @NonNull final TokenFeeScheduleUpdateTransactionBody op,
            @NonNull final WritableTokenStore tokenStore,
            @NonNull final TokensConfig config) {
        var token = TokenHandlerHelper.getIfUsable(op.tokenIdOrElse(TokenID.DEFAULT), tokenStore);
        validateTrue(token.hasFeeScheduleKey(), TOKEN_HAS_NO_FEE_SCHEDULE_KEY);
        validateTrue(op.customFees().size() <= config.maxCustomFeesAllowed(), CUSTOM_FEES_LIST_TOO_LONG);
        return token;
    }

    @NonNull
    @Override
    public Fees calculateFees(@NonNull final FeeContext feeContext) {
        final var body = feeContext.body();
        final var op = body.tokenFeeScheduleUpdateOrThrow();
        final var token = feeContext.readableStore(ReadableTokenStore.class).get(op.tokenIdOrThrow());
        final var tokenOpsUsage = new TokenOpsUsage();
        var newFees = op.customFees();

        // Ensure no null values for denominatingTokenId
        newFees = newFees.stream()
                .map(fee -> {
                    if (fee.hasFixedFee() && fee.fixedFee().denominatingTokenId() == null) {
                        return fee.copyBuilder()
                                .fixedFee(fee.fixedFee()
                                        .copyBuilder()
                                        .denominatingTokenId(TokenID.DEFAULT)
                                        .build())
                                .build();
                    }
                    return fee;
                })
                .toList();

        final var newReprBytes = tokenOpsUsage.bytesNeededToRepr(
                newFees.stream().map(CommonPbjConverters::fromPbj).toList());
        final var effConsTime =
                body.transactionIDOrThrow().transactionValidStartOrThrow().seconds();
        final var lifetime = Math.max(0, token == null ? 0 : token.expirationSecond() - effConsTime);
        final List<CustomFee> customFees = token == null ? emptyList() : token.customFees();

        final var existingFeeReprBytes = currentFeeScheduleSize(customFees, tokenOpsUsage);
        final var rbsDelta = ESTIMATOR_UTILS.changeInBsUsage(existingFeeReprBytes, lifetime, newReprBytes, lifetime);
        return feeContext
                .feeCalculatorFactory()
                .feeCalculator(SubType.DEFAULT)
                .addBytesPerTransaction(LONG_BASIC_ENTITY_ID_SIZE + newReprBytes)
                .addRamByteSeconds(rbsDelta)
                .calculate();
    }

    private int currentFeeScheduleSize(List<CustomFee> feeSchedule, final TokenOpsUsage tokenOpsUsage) {
        int numFixedHbarFees = 0;
        int numFixedHtsFees = 0;
        int numFractionalFees = 0;
        int numRoyaltyNoFallbackFees = 0;
        int numRoyaltyHtsFallbackFees = 0;
        int numRoyaltyHbarFallbackFees = 0;
        for (var fee : feeSchedule) {
            if (fee.fee().kind().equals(CustomFee.FeeOneOfType.FIXED_FEE)) {
                if (fee.fixedFee().hasDenominatingTokenId()) {
                    numFixedHtsFees++;
                } else {
                    numFixedHbarFees++;
                }
            } else if (fee.fee().kind().equals(CustomFee.FeeOneOfType.FRACTIONAL_FEE)) {
                numFractionalFees++;
            } else {
                final var royaltyFee = fee.royaltyFee();
                final var fallbackFee = royaltyFee.fallbackFee();
                if (fallbackFee != null) {
                    if (fallbackFee.hasDenominatingTokenId()) {
                        numRoyaltyHtsFallbackFees++;
                    } else {
                        numRoyaltyHbarFallbackFees++;
                    }
                } else {
                    numRoyaltyNoFallbackFees++;
                }
            }
        }
        return tokenOpsUsage.bytesNeededToRepr(
                numFixedHbarFees,
                numFixedHtsFees,
                numFractionalFees,
                numRoyaltyNoFallbackFees,
                numRoyaltyHtsFallbackFees,
                numRoyaltyHbarFallbackFees);
    }
}
