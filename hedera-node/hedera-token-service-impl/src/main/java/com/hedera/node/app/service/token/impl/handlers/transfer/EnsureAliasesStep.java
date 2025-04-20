// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers.transfer;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ALIAS_KEY;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * This is the first step in CryptoTransfer logic. This ensures that all aliases are resolved to their canonical forms.
 * The resolved forms are stored in TransferContext and then used in the rest of the transfer logic.
 */
public class EnsureAliasesStep implements TransferStep {
    private final CryptoTransferTransactionBody op;

    /**
     * Temporary token transfer resolutions map containing the token transfers to alias, is needed to check if
     * an alias is repeated. It is allowed to be repeated in multiple token transfer lists, but not in a single
     * token transfer list
     */
    private final Map<Bytes, AccountID> tokenTransferResolutions = new LinkedHashMap<>();

    /**
     * Constructs a {@link EnsureAliasesStep} instance.
     * @param op the crypto transfer transaction body
     */
    public EnsureAliasesStep(@NonNull final CryptoTransferTransactionBody op) {
        this.op = requireNonNull(op);
    }

    @Override
    public void doIn(@NonNull final TransferContext transferContext) {
        requireNonNull(transferContext);
        final var hbarTransfers = op.transfersOrElse(TransferList.DEFAULT).accountAmounts();
        final var tokenTransfers = op.tokenTransfers();
        // resolve hbar adjusts and add all alias resolutions to resolutions map in TransferContext
        resolveHbarAdjusts(hbarTransfers, transferContext);
        // resolve hbar adjusts and add all alias resolutions to resolutions map
        // and token resolutions map in TransferContext
        resolveTokenAdjusts(tokenTransfers, transferContext);
    }

    /**
     * Resolve token adjusts and add all alias resolutions to resolutions map in TransferContext.
     * If an accountID is an alias and is repeated within the same token transfer list, INVALID_ALIAS_KEY
     * is returned. If it is present in multiple transfer lists and is in resolutions map, it will be returned.
     * @param tokenTransfers the token transfers to resolve
     * @param transferContext the transfer context
     */
    private void resolveTokenAdjusts(
            @NonNull final List<TokenTransferList> tokenTransfers, @NonNull final TransferContext transferContext) {
        for (final var tt : tokenTransfers) {
            tokenTransferResolutions.clear();
            for (final var adjust : tt.transfers()) {
                if (isAlias(adjust.accountIDOrThrow())) {
                    final var account = resolveForFungibleToken(adjust, transferContext);
                    final var alias = adjust.accountIDOrThrow().alias();
                    tokenTransferResolutions.put(alias, account);
                    validateTrue(account != null, INVALID_ACCOUNT_ID);
                }
            }

            for (final var nftAdjust : tt.nftTransfers()) {
                resolveForNft(nftAdjust, transferContext);
            }
        }
    }

    private AccountID resolveForFungibleToken(
            @NonNull final AccountAmount adjust, @NonNull final TransferContext transferContext) {
        final var accountId = adjust.accountIDOrThrow();
        validateFalse(tokenTransferResolutions.containsKey(accountId.alias()), INVALID_ALIAS_KEY);
        final var account = transferContext.getFromAlias(accountId);
        if (account == null) {
            final var alias = accountId.alias();
            // If the token resolutions map already contains this unknown alias, we can assume
            // it was successfully auto-created by a prior mention in this CryptoTransfer.
            // (If it appeared in a sender location, this transfer will fail anyway.)
            final var isInResolutions = transferContext.resolutions().containsKey(alias);
            if (adjust.amount() > 0 && !isInResolutions) {
                transferContext.createFromAlias(alias, impliedAutoAssociationsForAlias(accountId, op));
            } else {
                validateTrue(transferContext.resolutions().containsKey(alias), INVALID_ACCOUNT_ID);
            }
            return transferContext.resolutions().get(alias);
        } else {
            return account;
        }
    }

    /**
     * Resolve hbar adjusts and add all alias resolutions to resolutions map in TransferContext.
     * If the accountID is an alias and is already in the resolutions map, it will be returned.
     * If the accountID is an alias and is not in the resolutions map, it will be autoCreated and
     * will be added to resolutions map.
     * @param hbarTransfers the hbar transfers to resolve
     * @param transferContext the transfer context
     */
    private void resolveHbarAdjusts(
            @NonNull final List<AccountAmount> hbarTransfers, @NonNull final TransferContext transferContext) {
        for (final var aa : hbarTransfers) {
            final var accountId = aa.accountIDOrThrow();
            if (isAlias(accountId)) {
                // If an alias is repeated for hbar transfers, it will fail
                final var isInResolutions = transferContext.resolutions().containsKey(accountId.alias());
                validateTrue(!isInResolutions, ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS);

                final var account = transferContext.getFromAlias(accountId);
                if (aa.amount() > 0) {
                    if (account == null) {
                        transferContext.createFromAlias(
                                accountId.alias(), impliedAutoAssociationsForAlias(accountId, op));
                    }
                } else {
                    validateTrue(account != null, INVALID_ACCOUNT_ID);
                }
            }
        }
    }

    /**
     * Resolve NFT adjusts and add all alias resolutions to resolutions map in TransferContext.
     * @param nftAdjust the NFT transfer to resolve
     * @param transferContext the transfer context
     */
    private void resolveForNft(@NonNull final NftTransfer nftAdjust, @NonNull final TransferContext transferContext) {
        final var receiverId = nftAdjust.receiverAccountIDOrThrow();
        final var senderId = nftAdjust.senderAccountIDOrThrow();
        // sender can't be a missing accountId. It will fail if the alias doesn't exist
        if (isAlias(senderId)) {
            final var sender = transferContext.getFromAlias(senderId);
            validateTrue(sender != null, INVALID_ACCOUNT_ID);
        }
        // Note a repeated alias is still valid for the NFT receiver case
        if (isAlias(receiverId)) {
            final var receiver = transferContext.getFromAlias(receiverId);
            if (receiver == null) {
                final var isInResolutions = transferContext.resolutions().containsKey(receiverId.alias());
                if (!isInResolutions) {
                    transferContext.createFromAlias(
                            receiverId.alias(), impliedAutoAssociationsForAlias(receiverId, op));
                }
            } else {
                validateTrue(receiver != null, INVALID_ACCOUNT_ID);
            }
        }
    }

    /**
     * Check if the given accountID is an alias.
     * @param accountID the accountID to check
     * @return true if the accountID is an alias, false otherwise
     */
    public static boolean isAlias(@NonNull AccountID accountID) {
        requireNonNull(accountID);
        return accountID.hasAlias() && accountID.accountNumOrElse(0L) == 0L;
    }

    /**
     * Returns the number of auto-associations implied by the given alias in the given
     * {@link com.hedera.hapi.node.token.CryptoTransferTransactionBody}.
     *
     * @param aliasedReceiverId the aliased receiver ID
     * @param op the crypto transfer transaction body
     * @return the number of auto-associations implied by the given alias
     */
    public static int impliedAutoAssociationsForAlias(
            @NonNull AccountID aliasedReceiverId, @NonNull CryptoTransferTransactionBody op) {
        requireNonNull(op);
        requireNonNull(aliasedReceiverId);
        int ans = 0;
        for (final var tokenTransferList : op.tokenTransfers()) {
            boolean impliedAutoAssociationHere = false;
            for (final var unitAdjust : tokenTransferList.transfers()) {
                if (unitAdjust.amount() > 0 && unitAdjust.accountIDOrThrow().equals(aliasedReceiverId)) {
                    impliedAutoAssociationHere = true;
                    break;
                }
            }
            // If impliedAutoAssociationHere is true, we don't need to check nftTransfers; but it will
            // also be empty because that means this token has fungible balance adjustments
            for (final var nftTransfer : tokenTransferList.nftTransfers()) {
                if (nftTransfer.receiverAccountIDOrThrow().equals(aliasedReceiverId)) {
                    impliedAutoAssociationHere = true;
                    break;
                }
            }
            if (impliedAutoAssociationHere) {
                ans++;
            }
        }
        return ans;
    }
}
