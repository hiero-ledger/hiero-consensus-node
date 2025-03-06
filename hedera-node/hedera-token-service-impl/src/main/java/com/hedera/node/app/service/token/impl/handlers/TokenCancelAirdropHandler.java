// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.EMPTY_PENDING_AIRDROP_ID_LIST;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NFT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_PENDING_AIRDROP_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_NFT_SERIAL_NUMBER;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.PENDING_AIRDROP_ID_LIST_TOO_LONG;
import static com.hedera.hapi.node.base.ResponseCodeEnum.PENDING_AIRDROP_ID_REPEATED;
import static com.hedera.node.app.service.token.impl.util.AirdropHandlerHelper.IdType.SENDER;
import static com.hedera.node.app.service.token.impl.util.AirdropHandlerHelper.standardizeAirdropIds;
import static com.hedera.node.app.spi.validation.Validations.validateAccountID;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.PendingAirdropId;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.token.TokenCancelAirdropTransactionBody;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableAirdropStore;
import com.hedera.node.app.service.token.impl.util.PendingAirdropUpdater;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.config.data.TokensConfig;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.EnumSet;
import java.util.HashSet;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#TOKEN_CANCEL_AIRDROP}.
 * This transaction type is used to cancel an airdrop which is in pending state.
 */
@Singleton
public class TokenCancelAirdropHandler extends BaseTokenHandler implements TransactionHandler {
    private final PendingAirdropUpdater pendingAirdropUpdater;

    @Inject
    public TokenCancelAirdropHandler(@NonNull final PendingAirdropUpdater pendingAirdropUpdater) {
        // Exists for injection
        this.pendingAirdropUpdater = requireNonNull(pendingAirdropUpdater);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var txn = context.body();
        final var op = txn.tokenCancelAirdropOrThrow();
        final var allPendingAirdrops = op.pendingAirdrops();
        for (final var airdrop : allPendingAirdrops) {
            context.requireAliasedKeyOrThrow(airdrop.senderIdOrThrow(), INVALID_PENDING_AIRDROP_ID);
        }
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var txn = context.body();
        requireNonNull(txn, "Transaction body cannot be null");
        final var op = txn.tokenCancelAirdropOrThrow();

        validateFalsePreCheck(op.pendingAirdrops().isEmpty(), EMPTY_PENDING_AIRDROP_ID_LIST);
        final var uniquePendingAirdrops = new HashSet<PendingAirdropId>();
        for (final var airdrop : op.pendingAirdrops()) {
            if (!uniquePendingAirdrops.add(airdrop)) {
                throw new PreCheckException(PENDING_AIRDROP_ID_REPEATED);
            }
            validateAccountID(airdrop.receiverId(), INVALID_PENDING_AIRDROP_ID);
            validateAccountID(airdrop.senderId(), INVALID_PENDING_AIRDROP_ID);

            if (airdrop.hasFungibleTokenType()) {
                final var tokenID = airdrop.fungibleTokenType();
                validateTruePreCheck(tokenID != null && !tokenID.equals(TokenID.DEFAULT), INVALID_TOKEN_ID);
            }
            if (airdrop.hasNonFungibleToken()) {
                final var nftID = airdrop.nonFungibleTokenOrThrow();
                validateTruePreCheck(nftID.tokenId() != null, INVALID_NFT_ID);
                validateTruePreCheck(nftID.serialNumber() > 0, INVALID_TOKEN_NFT_SERIAL_NUMBER);
            }
        }
    }

    @SuppressWarnings("java:S3864")
    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        requireNonNull(context);
        final var txn = context.body();
        final var op = txn.tokenCancelAirdropOrThrow();
        configValidation(context.configuration(), op);

        final var accountStore = context.storeFactory().writableStore(WritableAccountStore.class);
        final var airdropStore = context.storeFactory().writableStore(WritableAirdropStore.class);

        final var standardAirdropIds =
                standardizeAirdropIds(accountStore, airdropStore, op.pendingAirdrops(), EnumSet.of(SENDER));
        pendingAirdropUpdater.removePendingAirdrops(standardAirdropIds, airdropStore, accountStore);
    }

    /**
     * Using the configuration to validate if the body valid.
     */
    private void configValidation(
            @NonNull final Configuration configuration, @NonNull final TokenCancelAirdropTransactionBody op) {
        requireNonNull(configuration);
        requireNonNull(op);

        var tokensConfig = configuration.getConfigData(TokensConfig.class);
        validateTrue(tokensConfig.cancelTokenAirdropEnabled(), NOT_SUPPORTED);
        validateFalse(
                op.pendingAirdrops().size() > tokensConfig.maxAllowedPendingAirdropsToCancel(),
                PENDING_AIRDROP_ID_LIST_TOO_LONG);
    }

    @NonNull
    @Override
    public Fees calculateFees(@NonNull final FeeContext feeContext) {
        var tokensConfig = feeContext.configuration().getConfigData(TokensConfig.class);
        validateTrue(tokensConfig.cancelTokenAirdropEnabled(), NOT_SUPPORTED);
        final var feeCalculator = feeContext.feeCalculatorFactory().feeCalculator(SubType.DEFAULT);
        feeCalculator.resetUsage();

        return feeCalculator
                .addVerificationsPerTransaction(Math.max(0, feeContext.numTxnSignatures() - 1))
                .calculate();
    }
}
