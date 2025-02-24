// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers.transfer;

import static com.hedera.node.app.service.token.impl.handlers.transfer.EnsureAliasesStep.isAlias;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;

/**
 * Replaces aliases with IDs in the crypto transfer operation. This is needed to make rest of the steps in the
 * transfer handler to process easily .
 */
public class ReplaceAliasesWithIDsInOp {
    /**
     * Replaces aliases with IDs in the crypto transfer operation.It looks at the resolutions happened in {@link EnsureAliasesStep}
     * which are stored in {@link TransferContextImpl} and replaces aliases with IDs.
     * @param op the crypto transfer operation
     * @param transferContext the transfer context
     * @return the crypto transfer operation with aliases replaced with IDs
     */
    public CryptoTransferTransactionBody replaceAliasesWithIds(
            @NonNull final CryptoTransferTransactionBody op, @NonNull final TransferContextImpl transferContext) {
        requireNonNull(op);
        requireNonNull(transferContext);

        final var resolutions = transferContext.resolutions();
        final var replacedAliasesOp = CryptoTransferTransactionBody.newBuilder();
        final var transferList = TransferList.newBuilder();
        final var tokenTransfersList = new ArrayList<TokenTransferList>();
        final var accountAmounts = new ArrayList<AccountAmount>();
        // replace all aliases in hbar transfers
        for (final var aa : op.transfersOrElse(TransferList.DEFAULT).accountAmounts()) {
            if (isAlias(aa.accountIDOrThrow())) {
                final var resolvedId = resolutions.get(aa.accountIDOrThrow().alias());
                accountAmounts.add(aa.copyBuilder().accountID(resolvedId).build());
            } else {
                accountAmounts.add(aa);
            }
        }
        transferList.accountAmounts(accountAmounts);
        replacedAliasesOp.transfers(transferList);

        // replace all aliases in token transfers
        for (final var adjust : op.tokenTransfers()) {
            final var tokenTransferList = TokenTransferList.newBuilder().token(adjust.token());
            if (adjust.hasExpectedDecimals()) {
                tokenTransferList.expectedDecimals(adjust.expectedDecimals());
            }
            final List<AccountAmount> replacedTokenAdjusts = new ArrayList<>();
            for (final var tokenAdjust : adjust.transfers()) {
                if (isAlias(tokenAdjust.accountIDOrThrow())) {
                    final var resolvedId =
                            resolutions.get(tokenAdjust.accountID().alias());
                    replacedTokenAdjusts.add(
                            tokenAdjust.copyBuilder().accountID(resolvedId).build());
                } else {
                    replacedTokenAdjusts.add(tokenAdjust);
                }
            }
            if (!replacedTokenAdjusts.isEmpty()) {
                tokenTransferList.transfers(replacedTokenAdjusts);
            }
            // replace aliases in nft adjusts
            final List<NftTransfer> replacedNftAdjusts = new ArrayList<>();
            for (final var nftAdjust : adjust.nftTransfers()) {
                final var nftAdjustCopy = nftAdjust.copyBuilder();
                final var isReceiverAlias = isAlias(nftAdjust.receiverAccountIDOrThrow());
                final var isSenderAlias = isAlias(nftAdjust.senderAccountIDOrThrow());
                if (isReceiverAlias || isSenderAlias) {
                    if (isReceiverAlias) {
                        final var resolvedId = resolutions.get(
                                nftAdjust.receiverAccountIDOrThrow().alias());
                        nftAdjustCopy.receiverAccountID(resolvedId);
                    }
                    if (isSenderAlias) {
                        final var resolvedId = resolutions.get(
                                nftAdjust.senderAccountIDOrThrow().alias());
                        nftAdjustCopy.senderAccountID(resolvedId);
                    }
                    replacedNftAdjusts.add(nftAdjustCopy.build());
                } else {
                    replacedNftAdjusts.add(nftAdjust);
                }
            }
            // if there are any transfers or nft adjusts, add them to the token transfer list
            if (!replacedNftAdjusts.isEmpty()) {
                tokenTransferList.nftTransfers(replacedNftAdjusts);
            }
            tokenTransfersList.add(tokenTransferList.build());
        }
        replacedAliasesOp.tokenTransfers(tokenTransfersList);
        return replacedAliasesOp.build();
    }
}
