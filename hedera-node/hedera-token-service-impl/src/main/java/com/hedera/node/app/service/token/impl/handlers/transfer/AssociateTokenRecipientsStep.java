// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers.transfer;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN;
import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN;
import static com.hedera.hapi.node.base.ResponseCodeEnum.AMOUNT_EXCEEDS_ALLOWANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NFT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NO_REMAINING_AUTOMATIC_ASSOCIATIONS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SPENDER_DOES_NOT_HAVE_ALLOWANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hedera.node.app.service.token.impl.handlers.transfer.NFTOwnersChangeStep.validateSpenderHasAllowance;
import static com.hedera.node.app.service.token.impl.util.TokenHandlerHelper.getIfUsable;
import static com.hedera.node.app.service.token.impl.util.TokenHandlerHelper.getIfUsableForAliasedId;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.TokenAssociation;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.token.TokenAssociateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableNftStore;
import com.hedera.node.app.service.token.impl.WritableTokenRelationStore;
import com.hedera.node.app.service.token.impl.WritableTokenStore;
import com.hedera.node.app.service.token.impl.handlers.BaseTokenHandler;
import com.hedera.node.app.spi.workflows.ComputeDispatchFeesAsTopLevel;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.config.data.EntitiesConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;

/**
 * Associates the token with the sender and receiver accounts if they are not already associated.
 * They are auto-associated only if there are open auto-associations available on the account.
 */
public class AssociateTokenRecipientsStep extends BaseTokenHandler implements TransferStep {
    /**
     * With unlimited associations enabled, the fee computed by TokenAssociateToAccountHandler depends
     * only on the number of tokens associated and nothing else; so a placeholder transaction body works
     * fine for us when calling dispatchComputeFees().
     */
    public static final TransactionBody PLACEHOLDER_SYNTHETIC_ASSOCIATION = TransactionBody.newBuilder()
            .tokenAssociate(TokenAssociateTransactionBody.newBuilder()
                    .account(AccountID.DEFAULT)
                    .tokens(TokenID.DEFAULT)
                    .build())
            .build();

    private final CryptoTransferTransactionBody op;

    /**
     * Constructs the step with the operation.
     * @param op the operation
     */
    public AssociateTokenRecipientsStep(@NonNull final CryptoTransferTransactionBody op) {
        this.op = requireNonNull(op);
    }

    @Override
    public void doIn(@NonNull final TransferContext transferContext) {
        requireNonNull(transferContext);
        final var handleContext = transferContext.getHandleContext();
        final var storeFactory = handleContext.storeFactory();
        final var tokenStore = storeFactory.writableStore(WritableTokenStore.class);
        final var tokenRelStore = storeFactory.writableStore(WritableTokenRelationStore.class);
        final var accountStore = storeFactory.writableStore(WritableAccountStore.class);
        final var nftStore = storeFactory.writableStore(WritableNftStore.class);
        final List<TokenAssociation> newAssociations = new ArrayList<>();

        for (final var xfers : op.tokenTransfers()) {
            final var tokenId = xfers.tokenOrThrow();
            final var token = getIfUsable(tokenId, tokenStore);

            for (final var aa : xfers.transfers()) {
                final var accountId = aa.accountIDOrElse(AccountID.DEFAULT);
                final TokenAssociation newAssociation;
                try {
                    newAssociation = validateAndBuildAutoAssociation(
                            accountId, tokenId, token, accountStore, tokenRelStore, handleContext);
                } catch (HandleException e) {
                    // (FUTURE) Remove this catch and stop translating TOKEN_NOT_ASSOCIATED_TO_ACCOUNT
                    // into e.g. SPENDER_DOES_NOT_HAVE_ALLOWANCE; we need this only for mono-service
                    // fidelity during diff testing
                    if (mayNeedTranslation(e, aa)) {
                        validateFungibleAllowance(
                                requireNonNull(accountStore.getAccountById(aa.accountIDOrThrow())),
                                handleContext.payer(),
                                tokenId,
                                aa.amount());
                    }
                    throw e;
                }
                if (newAssociation != null) {
                    newAssociations.add(newAssociation);
                }
            }

            for (final var nftTransfer : xfers.nftTransfers()) {
                final var receiverId = nftTransfer.receiverAccountIDOrElse(AccountID.DEFAULT);
                final var senderId = nftTransfer.senderAccountIDOrElse(AccountID.DEFAULT);
                // sender should be associated already. If not throw exception
                final var nft = nftStore.get(tokenId, nftTransfer.serialNumber());
                try {
                    validateTrue(tokenRelStore.get(senderId, tokenId) != null, TOKEN_NOT_ASSOCIATED_TO_ACCOUNT);
                } catch (HandleException e) {
                    // (FUTURE) Remove this catch and stop translating TOKEN_NOT_ASSOCIATED_TO_ACCOUNT
                    // into e.g. SPENDER_DOES_NOT_HAVE_ALLOWANCE; we need this only for mono-service
                    // fidelity during diff testing
                    if (nft != null && mayNeedTranslation(e, nftTransfer)) {
                        validateSpenderHasAllowance(
                                requireNonNull(accountStore.getAccountById(senderId)),
                                handleContext.payer(),
                                tokenId,
                                nft);
                    }
                    throw e;
                }
                validateTrue(nft != null, INVALID_NFT_ID);
                final var newAssociation = validateAndBuildAutoAssociation(
                        receiverId, tokenId, token, accountStore, tokenRelStore, handleContext);
                if (newAssociation != null) {
                    newAssociations.add(newAssociation);
                }
            }
        }

        for (TokenAssociation newAssociation : newAssociations) {
            transferContext.addToAutomaticAssociations(newAssociation);
        }
    }

    private boolean mayNeedTranslation(final HandleException exception, final AccountAmount adjustment) {
        return exception.getStatus() == TOKEN_NOT_ASSOCIATED_TO_ACCOUNT
                && adjustment.isApproval()
                && adjustment.amount() < 0;
    }

    private boolean mayNeedTranslation(final HandleException exception, final NftTransfer nftTransfer) {
        return exception.getStatus() == TOKEN_NOT_ASSOCIATED_TO_ACCOUNT && nftTransfer.isApproval();
    }

    /**
     * Associates the token with the account if it is not already associated. It is auto-associated only if there are
     * open auto-associations available on the account.
     *
     * @param accountId The account to associate the token with
     * @param tokenId The tokenID of the token to associate with the account
     * @param token The token to associate with the account
     * @param accountStore The account store
     * @param tokenRelStore The token relation store
     * @param context The context
     */
    private TokenAssociation validateAndBuildAutoAssociation(
            @NonNull final AccountID accountId,
            @NonNull final TokenID tokenId,
            @NonNull final Token token,
            @NonNull final WritableAccountStore accountStore,
            @NonNull final WritableTokenRelationStore tokenRelStore,
            @NonNull final HandleContext context) {
        final var account =
                getIfUsableForAliasedId(accountId, accountStore, context.expiryValidator(), INVALID_ACCOUNT_ID);
        final var tokenRel = tokenRelStore.get(account.accountIdOrThrow(), tokenId);
        final var config = context.configuration();
        final var entitiesConfig = config.getConfigData(EntitiesConfig.class);

        if (tokenRel == null && account.maxAutoAssociations() != 0) {
            boolean validAssociations = hasUnlimitedAutoAssociations(account, entitiesConfig)
                    || account.usedAutoAssociations() < account.maxAutoAssociations();
            validateTrue(validAssociations, NO_REMAINING_AUTOMATIC_ASSOCIATIONS);
            validateFalse(token.hasKycKey(), ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN);
            validateFalse(token.accountsFrozenByDefault(), ACCOUNT_FROZEN_FOR_TOKEN);

            // We only charge auto-association fees inline if this is a user dispatch; for internal dispatches,
            // the contract service will take the auto-association costs from the remaining EVM gas
            if (context.savepointStack().getBaseBuilder(StreamBuilder.class).isUserDispatch()) {
                final var unlimitedAssociationsEnabled =
                        config.getConfigData(EntitiesConfig.class).unlimitedAutoAssociationsEnabled();
                // And the "sender pays" fee model only applies when using unlimited auto-associations
                if (unlimitedAssociationsEnabled) {
                    final var autoAssociationFee = associationFeeFor(context, PLACEHOLDER_SYNTHETIC_ASSOCIATION);
                    if (!context.tryToChargePayer(autoAssociationFee)) {
                        throw new HandleException(INSUFFICIENT_PAYER_BALANCE);
                    }
                }
            }
            final var newRelation =
                    autoAssociate(account.accountIdOrThrow(), token, accountStore, tokenRelStore, config);
            return asTokenAssociation(newRelation.tokenId(), newRelation.accountId());
        } else {
            validateTrue(tokenRel != null, TOKEN_NOT_ASSOCIATED_TO_ACCOUNT);
            validateFalse(tokenRel.frozen(), ACCOUNT_FROZEN_FOR_TOKEN);
            return null;
        }
    }

    public static long associationFeeFor(@NonNull final HandleContext context, @NonNull final TransactionBody txnBody) {
        return context.dispatchComputeFees(txnBody, context.payer(), ComputeDispatchFeesAsTopLevel.NO)
                .totalFee();
    }

    private void validateFungibleAllowance(
            @NonNull final Account account,
            @NonNull final AccountID topLevelPayer,
            @NonNull final TokenID tokenId,
            final long amount) {
        final var tokenAllowances = account.tokenAllowances();
        for (final var allowance : tokenAllowances) {
            if (topLevelPayer.equals(allowance.spenderId()) && tokenId.equals(allowance.tokenId())) {
                final var newAllowanceAmount = allowance.amount() + amount;
                validateTrue(newAllowanceAmount >= 0, AMOUNT_EXCEEDS_ALLOWANCE);
                return;
            }
        }
        throw new HandleException(SPENDER_DOES_NOT_HAVE_ALLOWANCE);
    }
}
