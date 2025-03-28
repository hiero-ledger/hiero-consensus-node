// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.util;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSFER_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TREASURY_ACCOUNT_FOR_TOKEN;
import static com.hedera.hapi.util.HapiUtils.isHollow;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.isValid;
import static com.hedera.node.app.service.token.AliasUtils.isAlias;
import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.isStakingAccount;
import static com.hedera.node.app.service.token.impl.handlers.transfer.TransferExecutor.OptionalKeyCheck.RECEIVER_KEY_IS_OPTIONAL;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.handlers.transfer.TransferExecutor;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import edu.umd.cs.findbugs.annotations.Nullable;

public class CryptoTransferValidationHelper {

    private CryptoTransferValidationHelper() {
        throw new IllegalStateException("Utility class");
    }

    public static void checkSender(
            final AccountID senderId,
            final NftTransfer nftTransfer,
            final PreHandleContext meta,
            final ReadableAccountStore accountStore)
            throws PreCheckException {

        // Lookup the sender account and verify it.
        final var senderAccount = accountStore.getAliasedAccountById(senderId);
        if (senderAccount == null) {
            throw new PreCheckException(INVALID_ACCOUNT_ID);
        }

        // If the sender account is immutable, then we throw an exception.
        final var key = senderAccount.key();
        if (key == null || !isValid(key)) {
            if (isHollow(senderAccount)) {
                meta.requireSignatureForHollowAccount(senderAccount);
            } else {
                // If the sender account has no key, then fail with INVALID_ACCOUNT_ID.
                // NOTE: should change to ACCOUNT_IS_IMMUTABLE
                throw new PreCheckException(INVALID_ACCOUNT_ID);
            }
        } else if (!nftTransfer.isApproval()) {
            meta.requireKey(key);
        }
    }

    public static void checkReceiver(
            final AccountID receiverId,
            final AccountID senderId,
            final NftTransfer nftTransfer,
            final PreHandleContext meta,
            final ReadableTokenStore.TokenMetadata tokenMeta,
            @Nullable final CryptoTransferTransactionBody op,
            final ReadableAccountStore accountStore,
            final TransferExecutor.OptionalKeyCheck receiverKeyCheck)
            throws PreCheckException {

        // Lookup the receiver account and verify it.
        final var receiverAccount = accountStore.getAliasedAccountById(receiverId);
        if (receiverAccount == null) {
            // It may be that the receiver account does not yet exist. If it is being addressed by alias,
            // then this is OK, as we will automatically create the account. Otherwise, fail.
            if (!isAlias(receiverId)) {
                throw new PreCheckException(INVALID_ACCOUNT_ID);
            } else {
                return;
            }
        }

        final var receiverKey = receiverAccount.key();
        if (isStakingAccount(meta.configuration(), receiverAccount.accountId())) {
            // If the receiver account has no key, then fail with INVALID_ACCOUNT_ID.
            // NOTE: should change to ACCOUNT_IS_IMMUTABLE after modularization
            throw new PreCheckException(INVALID_ACCOUNT_ID);
        } else if (receiverAccount.receiverSigRequired()) {
            if (receiverKeyCheck == RECEIVER_KEY_IS_OPTIONAL) {
                meta.optionalKey(receiverKey);
            } else {
                // If receiverSigRequired is set, and if there is no key on the receiver's account, then fail with
                // INVALID_TRANSFER_ACCOUNT_ID. Otherwise, add the key.
                meta.requireKeyOrThrow(receiverKey, INVALID_TRANSFER_ACCOUNT_ID);
            }
        } else if (tokenMeta.hasRoyaltyWithFallback()) {
            // For airdrops, we don't support tokens with royalties with fallback
            if (op == null) {
                throw new PreCheckException(INVALID_TRANSACTION);
            } else if (!receivesFungibleValue(nftTransfer.senderAccountID(), op, accountStore)) {
                // It may be that this transfer has royalty fees associated with it. If it does, then we need
                // to check that the receiver signed the transaction, UNLESS the sender or receiver is
                // the treasury, in which case fallback fees will not be applied when the transaction is handled,
                // so the receiver key does not need to sign.
                final var treasuryId = tokenMeta.treasuryAccountId();
                if (!treasuryId.equals(senderId) && !treasuryId.equals(receiverId)) {
                    meta.requireKeyOrThrow(receiverId, INVALID_TREASURY_ACCOUNT_FOR_TOKEN);
                }
            }
        }
    }

    private static boolean receivesFungibleValue(
            final AccountID target, final CryptoTransferTransactionBody op, final ReadableAccountStore accountStore) {
        for (final var adjust : op.transfersOrElse(TransferList.DEFAULT).accountAmounts()) {
            final var unaliasedAccount = accountStore.getAliasedAccountById(adjust.accountIDOrElse(AccountID.DEFAULT));
            final var unaliasedTarget = accountStore.getAliasedAccountById(target);
            if (unaliasedAccount != null && adjust.amount() > 0 && unaliasedAccount.equals(unaliasedTarget)) {
                return true;
            }
        }
        for (final var transfers : op.tokenTransfers()) {
            for (final var adjust : transfers.transfers()) {
                final var unaliasedAccount =
                        accountStore.getAliasedAccountById(adjust.accountIDOrElse(AccountID.DEFAULT));
                final var unaliasedTarget = accountStore.getAliasedAccountById(target);
                if (unaliasedAccount != null && adjust.amount() > 0 && unaliasedAccount.equals(unaliasedTarget)) {
                    return true;
                }
            }
        }
        return false;
    }
}
