// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.EMPTY_PENDING_AIRDROP_ID_LIST;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_PENDING_AIRDROP_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.PENDING_AIRDROP_ID_LIST_TOO_LONG;
import static com.hedera.hapi.node.base.ResponseCodeEnum.PENDING_AIRDROP_ID_REPEATED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_AIRDROP_WITH_FALLBACK_ROYALTY;
import static com.hedera.node.app.service.token.impl.util.TokenHandlerHelper.getIfUsable;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.PendingAirdropId;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.token.TokenClaimAirdropTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableAirdropStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableAirdropStore;
import com.hedera.node.app.service.token.impl.WritableTokenRelationStore;
import com.hedera.node.app.service.token.impl.handlers.transfer.TransferContextImpl;
import com.hedera.node.app.service.token.impl.handlers.transfer.TransferExecutor;
import com.hedera.node.app.service.token.impl.handlers.transfer.hooks.HookCallFactory;
import com.hedera.node.app.service.token.impl.util.AirdropHandlerHelper;
import com.hedera.node.app.service.token.impl.util.PendingAirdropUpdater;
import com.hedera.node.app.service.token.impl.validators.CryptoTransferValidator;
import com.hedera.node.app.service.token.impl.validators.TokenAirdropValidator;
import com.hedera.node.app.service.token.records.CryptoTransferStreamBuilder;
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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link
 * HederaFunctionality#TOKEN_CLAIM_AIRDROP}.
 */
@Singleton
public class TokenClaimAirdropHandler extends TransferExecutor implements TransactionHandler {
    private final TokenAirdropValidator validator;
    private final PendingAirdropUpdater pendingAirdropUpdater;

    @Inject
    public TokenClaimAirdropHandler(
            @NonNull final TokenAirdropValidator validator,
            @NonNull final CryptoTransferValidator cryptoTransferValidator,
            @NonNull final PendingAirdropUpdater pendingAirdropUpdater,
            @NonNull final HookCallFactory hookCallFactory) {
        super(cryptoTransferValidator, hookCallFactory);
        this.validator = validator;
        this.pendingAirdropUpdater = pendingAirdropUpdater;
    }

    @Override
    public void preHandle(@NonNull PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = requireNonNull(context.body().tokenClaimAirdrop());
        final var pendingAirdrops = op.pendingAirdrops();

        for (final var pendingAirdrop : pendingAirdrops) {
            final var receiverId = pendingAirdrop.receiverIdOrThrow();
            context.requireAliasedKeyOrThrow(receiverId, INVALID_ACCOUNT_ID);
        }
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var txn = context.body();
        requireNonNull(txn);

        final var op = txn.tokenClaimAirdrop();
        requireNonNull(op);

        final var pendingAirdrops = op.pendingAirdrops();
        validateTruePreCheck(!pendingAirdrops.isEmpty(), EMPTY_PENDING_AIRDROP_ID_LIST);

        final var uniqueAirdrops = Set.copyOf(pendingAirdrops);
        validateTruePreCheck(pendingAirdrops.size() == uniqueAirdrops.size(), PENDING_AIRDROP_ID_REPEATED);

        validateTruePreCheck(
                pendingAirdrops.stream().allMatch(PendingAirdropId::hasSenderId), INVALID_PENDING_AIRDROP_ID);
        validateTruePreCheck(
                pendingAirdrops.stream().allMatch(PendingAirdropId::hasReceiverId), INVALID_PENDING_AIRDROP_ID);
    }

    @Override
    public void handle(@NonNull HandleContext context) throws HandleException {
        final var op = context.body().tokenClaimAirdropOrThrow();

        final var pendingAirdropStore = context.storeFactory().writableStore(WritableAirdropStore.class);
        final var accountStore = context.storeFactory().writableStore(WritableAccountStore.class);
        final var tokenStore = context.storeFactory().readableStore(ReadableTokenStore.class);
        final var tokenRelStore = context.storeFactory().writableStore(WritableTokenRelationStore.class);
        final var recordBuilder = context.savepointStack().getBaseBuilder(CryptoTransferStreamBuilder.class);

        final var validatedAirdropIds = validateSemantics(context, op, accountStore);

        final Map<TokenID, TokenTransferList> transfers = new HashMap<>();
        final var tokensToAssociate = new LinkedHashMap<AccountID, Set<Token>>();

        // 1. validate pending airdrops and create transfer lists
        for (var airdropId : validatedAirdropIds) {
            final var tokenId = airdropId.hasFungibleTokenType()
                    ? airdropId.fungibleTokenTypeOrThrow()
                    : airdropId.nonFungibleTokenOrThrow().tokenIdOrThrow();
            final var senderId = airdropId.senderIdOrThrow();
            final var receiverId = airdropId.receiverIdOrThrow();

            // Merge this transfer by token id into the transfers map
            createOrUpdateTransfers(airdropId, pendingAirdropStore, tokenId, senderId, receiverId, transfers);

            // check if we need new association
            if (tokenRelStore.get(receiverId, tokenId) == null) {
                tokensToAssociate
                        .computeIfAbsent(receiverId, k -> new LinkedHashSet<>())
                        .add(getIfUsable(tokenId, tokenStore));
            }
        }
        for (var entry : tokensToAssociate.entrySet()) {
            associateForFree(entry.getValue().stream().toList(), entry.getKey(), accountStore, tokenRelStore);
        }
        // do the crypto transfer
        transferForFree(new ArrayList<>(transfers.values()), context, recordBuilder);
        pendingAirdropUpdater.removePendingAirdrops(validatedAirdropIds, pendingAirdropStore, accountStore);
    }

    /**
     * Validates the semantics of the token claim airdrop transaction.
     *
     * @param context the handle context
     * @param op the token claim airdrop transaction body
     * @param accountStore the account store
     * @return a list of validated pending airdrop ids using the {@code 0.0.X} reference for both sender and receiver
     * @throws HandleException if the transaction is invalid
     */
    private Set<PendingAirdropId> validateSemantics(
            @NonNull HandleContext context,
            @NonNull TokenClaimAirdropTransactionBody op,
            @NonNull final ReadableAccountStore accountStore)
            throws HandleException {
        final var tokensConfig = context.configuration().getConfigData(TokensConfig.class);
        validateTrue(
                op.pendingAirdrops().size() <= tokensConfig.maxAllowedPendingAirdropsToClaim(),
                PENDING_AIRDROP_ID_LIST_TOO_LONG);

        final var tokenStore = context.storeFactory().readableStore(ReadableTokenStore.class);
        final var pendingAirdropStore = context.storeFactory().readableStore(ReadableAirdropStore.class);
        final var standardAirdropIds = AirdropHandlerHelper.standardizeAirdropIds(
                accountStore,
                pendingAirdropStore,
                op.pendingAirdrops(),
                EnumSet.of(AirdropHandlerHelper.IdType.RECEIVER));
        for (final var airdrop : standardAirdropIds) {
            final var tokenId = airdrop.hasFungibleTokenType()
                    ? airdrop.fungibleTokenTypeOrThrow()
                    : airdrop.nonFungibleTokenOrThrow().tokenIdOrThrow();
            getIfUsable(tokenId, tokenStore);
            validateTrue(
                    validator.tokenHasNoRoyaltyWithFallbackFee(tokenId, tokenStore),
                    TOKEN_AIRDROP_WITH_FALLBACK_ROYALTY);
        }
        return standardAirdropIds;
    }

    @Override
    public Fees calculateFees(@NonNull FeeContext feeContext) {
        var tokensConfig = feeContext.configuration().getConfigData(TokensConfig.class);
        validateTrue(tokensConfig.airdropsClaimEnabled(), ResponseCodeEnum.NOT_SUPPORTED);
        final var feeCalculator = feeContext.feeCalculatorFactory().feeCalculator(SubType.DEFAULT);
        feeCalculator.resetUsage();

        return feeCalculator
                .addVerificationsPerTransaction(Math.max(0, feeContext.numTxnSignatures() - 1))
                .calculate();
    }

    private void createOrUpdateTransfers(
            @NonNull final PendingAirdropId airdrop,
            @NonNull final WritableAirdropStore airdropStore,
            @NonNull final TokenID tokenId,
            @NonNull final AccountID senderId,
            @NonNull final AccountID receiverId,
            @NonNull final Map<TokenID, TokenTransferList> transfers) {
        final var accountPendingAirdrop = requireNonNull(airdropStore.get(airdrop));
        final var soFar = transfers.computeIfAbsent(
                tokenId, k -> TokenTransferList.newBuilder().token(tokenId).build());
        if (airdrop.hasFungibleTokenType()) {
            // process fungible tokens
            final var senderAccountAmount = asAccountAmount(
                    senderId,
                    -accountPendingAirdrop.pendingAirdropValueOrThrow().amount());
            final var receiverAccountAmount = asAccountAmount(
                    receiverId,
                    accountPendingAirdrop.pendingAirdropValueOrThrow().amount());
            final List<AccountAmount> newTransfers = new ArrayList<>(soFar.transfers());
            mergeTransfer(newTransfers, senderAccountAmount);
            mergeTransfer(newTransfers, receiverAccountAmount);
            transfers.put(tokenId, soFar.copyBuilder().transfers(newTransfers).build());
        } else {
            // process non-fungible tokens
            final var nftTransfer = NftTransfer.newBuilder()
                    .senderAccountID(senderId)
                    .receiverAccountID(receiverId)
                    .serialNumber(airdrop.nonFungibleTokenOrThrow().serialNumber())
                    .build();
            final List<NftTransfer> newTransfers = new ArrayList<>(soFar.nftTransfers());
            newTransfers.add(nftTransfer);
            transfers.put(
                    tokenId, soFar.copyBuilder().nftTransfers(newTransfers).build());
        }
    }

    private void mergeTransfer(@NonNull final List<AccountAmount> transfers, @NonNull final AccountAmount newTransfer) {
        final var accountId = newTransfer.accountIDOrThrow();
        for (int i = 0, n = transfers.size(); i < n; i++) {
            if (transfers.get(i).accountIDOrThrow().equals(accountId)) {
                final var updatedTransfer = transfers
                        .get(i)
                        .copyBuilder()
                        .amount(transfers.get(i).amount() + newTransfer.amount())
                        .build();
                transfers.set(i, updatedTransfer);
                return;
            }
        }
        transfers.add(newTransfer);
    }

    private void associateForFree(
            @NonNull final List<Token> tokensToAssociate,
            @NonNull final AccountID receiverId,
            @NonNull final WritableAccountStore accountStore,
            @NonNull final WritableTokenRelationStore tokenRelStore) {
        createAndLinkTokenRels(
                requireNonNull(accountStore.getAccountById(receiverId)),
                tokensToAssociate,
                accountStore,
                tokenRelStore);
    }

    private void transferForFree(
            @NonNull final List<TokenTransferList> transfers,
            @NonNull final HandleContext context,
            @NonNull final CryptoTransferStreamBuilder recordBuilder) {
        final var cryptoTransferBody = CryptoTransferTransactionBody.newBuilder()
                .tokenTransfers(transfers)
                .build();
        final var syntheticCryptoTransferTxn =
                TransactionBody.newBuilder().cryptoTransfer(cryptoTransferBody).build();
        final var transferContext = new TransferContextImpl(context, cryptoTransferBody, true);
        // We should skip custom fee steps here, because they must be already prepaid
        executeCryptoTransferWithoutCustomFee(syntheticCryptoTransferTxn, transferContext, context, recordBuilder);
    }

    public static AccountAmount asAccountAmount(final AccountID account, final long amount) {
        return AccountAmount.newBuilder().accountID(account).amount(amount).build();
    }
}
