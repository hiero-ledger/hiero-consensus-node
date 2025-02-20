// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers.transfer;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NFT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SENDER_DOES_NOT_OWN_NFT_SERIAL_NO;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SPENDER_DOES_NOT_HAVE_ALLOWANCE;
import static com.hedera.node.app.service.token.impl.util.TokenHandlerHelper.getIfUsable;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.AccountApprovalForAllAllowance;
import com.hedera.hapi.node.state.token.Nft;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableNftStore;
import com.hedera.node.app.service.token.impl.WritableTokenRelationStore;
import com.hedera.node.app.service.token.impl.WritableTokenStore;
import com.hedera.node.app.service.token.impl.handlers.BaseTokenHandler;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * Handles the ownership change of NFTs in a token transfer.
 */
public class NFTOwnersChangeStep extends BaseTokenHandler implements TransferStep {
    private final List<TokenTransferList> tokenTransferLists;
    private final AccountID topLevelPayer;

    /**
     * Constructs the {@link NFTOwnersChangeStep} with the given {@link CryptoTransferTransactionBody} and payer
     * {@link AccountID}.
     * @param tokenTransferLists the {@link List} of {@link TokenTransferList}
     * @param topLevelPayer the payer {@link AccountID}
     */
    public NFTOwnersChangeStep(final List<TokenTransferList> tokenTransferLists, final AccountID topLevelPayer) {
        this.tokenTransferLists = tokenTransferLists;
        this.topLevelPayer = topLevelPayer;
    }

    @Override
    public void doIn(final TransferContext transferContext) {
        final var handleContext = transferContext.getHandleContext();
        final var storeFactory = handleContext.storeFactory();
        final var nftStore = storeFactory.writableStore(WritableNftStore.class);
        final var accountStore = storeFactory.writableStore(WritableAccountStore.class);
        final var tokenStore = storeFactory.writableStore(WritableTokenStore.class);
        final var tokenRelStore = storeFactory.writableStore(WritableTokenRelationStore.class);
        final var expiryValidator = handleContext.expiryValidator();

        for (var xfers : tokenTransferLists) {
            final var tokenId = xfers.token();
            final var token = getIfUsable(tokenId, tokenStore);
            // Expected decimals are already validated in AdjustFungibleTokenChangesStep.
            // So not doing same check again here

            for (final var nftTransfer : xfers.nftTransfers()) {
                final var senderId = nftTransfer.senderAccountIDOrThrow();
                final var receiverId = nftTransfer.receiverAccountIDOrThrow();
                final var serial = nftTransfer.serialNumber();

                final var senderAccount = getIfUsable(senderId, accountStore, expiryValidator, INVALID_ACCOUNT_ID);
                final var receiverAccount = getIfUsable(receiverId, accountStore, expiryValidator, INVALID_ACCOUNT_ID);
                final var senderRel = getIfUsable(senderId, tokenId, tokenRelStore);
                final var receiverRel = getIfUsable(receiverId, tokenId, tokenRelStore);
                validateNotFrozenAndKycOnRelation(senderRel);
                validateNotFrozenAndKycOnRelation(receiverRel);

                final var treasuryId = token.treasuryAccountId();
                getIfUsable(treasuryId, accountStore, expiryValidator, INVALID_ACCOUNT_ID);
                final var nft = nftStore.get(tokenId, serial);
                validateTrue(nft != null, INVALID_NFT_ID);

                if (nftTransfer.isApproval()) {
                    // If isApproval flag is set then the spender account must have paid for the transaction.
                    // The transfer list specifies the owner who granted allowance as sender
                    // check if the allowances from the sender account has the payer account as spender
                    validateSpenderHasAllowance(senderAccount, topLevelPayer, tokenId, nft);
                }

                // owner of nft should match the sender in transfer list
                if (nft.hasOwnerId()) {
                    validateTrue(nft.ownerId().equals(senderId), SENDER_DOES_NOT_OWN_NFT_SERIAL_NO);
                } else {
                    validateTrue(treasuryId.equals(senderId), SENDER_DOES_NOT_OWN_NFT_SERIAL_NO);
                }

                // Update the ownership of the nft
                updateOwnership(
                        nft,
                        treasuryId,
                        senderAccount,
                        receiverAccount,
                        senderRel,
                        receiverRel,
                        accountStore,
                        tokenRelStore,
                        nftStore);
            }
        }
    }

    /**
     * Validate if the spender has allowance to transfer the nft, if the nft is being transferred
     * with an isApproval flag set to true.
     *
     * @param owner owner of the nft
     * @param spender spender of the nft
     * @param tokenId token id of the nft
     * @param nft nft to be transferred
     */
    static void validateSpenderHasAllowance(
            final Account owner, final AccountID spender, final TokenID tokenId, final Nft nft) {
        final var approveForAllAllowances = owner.approveForAllNftAllowances();
        final var allowance = AccountApprovalForAllAllowance.newBuilder()
                .spenderId(spender)
                .tokenId(tokenId)
                .build();
        if (!approveForAllAllowances.contains(allowance)) {
            final var approvedSpender = nft.spenderId();
            validateTrue(approvedSpender != null && approvedSpender.equals(spender), SPENDER_DOES_NOT_HAVE_ALLOWANCE);
        }
    }

    /**
     * Update the ownership of the nft. It updates the owner of NFT to {@link AccountID#DEFAULT}
     * if it is being transferred back to treasury account. It also updates the number of NFTs
     * owned and number of positive balances by the sender and receiver accounts.
     * Also updates the token relations balance for both sender and receiver accounts.
     * It also wipes the spender on the nft if the nft is being transferred, since
     * owner no more owns the NFT.
     *
     * @param nft - NFT to be transferred
     * @param treasuryId - Treasury account of the token
     * @param senderAccount - Sender account
     * @param receiverAccount - Receiver account
     * @param senderRel - Token relation of sender account
     * @param receiverRel - Token relation of receiver account
     * @param accountStore - Account store
     * @param tokenRelStore - Token relation store
     * @param nftStore - NFT store
     */
    private void updateOwnership(
            @NonNull final Nft nft,
            @NonNull final AccountID treasuryId,
            @NonNull final Account senderAccount,
            @NonNull final Account receiverAccount,
            final TokenRelation senderRel,
            final TokenRelation receiverRel,
            final WritableAccountStore accountStore,
            final WritableTokenRelationStore tokenRelStore,
            final WritableNftStore nftStore) {
        final var nftCopy = nft.copyBuilder();

        final var fromNftsOwned = senderAccount.numberOwnedNfts();
        final var toNftsOwned = receiverAccount.numberOwnedNfts();
        final var fromTokenRelBalance = senderRel.balance();
        final var toTokenRelBalance = receiverRel.balance();
        final var fromNumPositiveBalances = senderAccount.numberPositiveBalances();
        final var toNumPositiveBalances = receiverAccount.numberPositiveBalances();
        final var isTreasuryReturn = treasuryId.equals(receiverAccount.accountId());
        final var isSenderTreasury = treasuryId.equals(senderAccount.accountId());

        // If the token is being returned back to treasury null out the owner
        if (isTreasuryReturn) {
            nftCopy.ownerId((AccountID) null);
        } else {
            nftCopy.ownerId(receiverAccount.accountId());
        }
        // wipe the spender on this NFT
        nftCopy.spenderId((AccountID) null);
        nftStore.put(nftCopy.build());

        // adjust number of positive balances
        final var updatedFromPositiveBalances =
                fromTokenRelBalance - 1 == 0 ? fromNumPositiveBalances - 1 : fromNumPositiveBalances;
        final var updatedToPositiveBalances =
                toTokenRelBalance == 0 ? toNumPositiveBalances + 1 : toNumPositiveBalances;
        // update links
        updateLinks(
                senderAccount,
                receiverAccount,
                nft.nftIdOrThrow(),
                isSenderTreasury,
                isTreasuryReturn,
                nftStore,
                accountStore);

        // Make copies of the objects to be updated
        final var senderAccountCopy = requireNonNull(accountStore.get(senderAccount.accountIdOrThrow()))
                .copyBuilder();
        final var receiverAccountCopy = requireNonNull(accountStore.get(receiverAccount.accountIdOrThrow()))
                .copyBuilder();
        final var senderRelCopy = senderRel.copyBuilder();
        final var receiverRelCopy = receiverRel.copyBuilder();

        accountStore.put(senderAccountCopy
                .numberOwnedNfts(fromNftsOwned - 1)
                .numberPositiveBalances(updatedFromPositiveBalances)
                .build());
        accountStore.put(receiverAccountCopy
                .numberOwnedNfts(toNftsOwned + 1)
                .numberPositiveBalances(updatedToPositiveBalances)
                .build());
        tokenRelStore.put(senderRelCopy.balance(fromTokenRelBalance - 1).build());
        tokenRelStore.put(receiverRelCopy.balance(toTokenRelBalance + 1).build());
    }

    /**
     * Update the linked list of NFTs for the sender and receiver accounts.
     * @param from - Sender account
     * @param to - Receiver account
     * @param nftId - NFT id
     * @param isSenderTreasury - Flag to indicate if sender is treasury
     * @param isReceiverTreasury - Flag to indicate if receiver is treasury
     * @param nftStore - NFT store
     * @param accountStore - Account store
     */
    public void updateLinks(
            @NonNull final Account from,
            @NonNull final Account to,
            @NonNull final NftID nftId,
            final boolean isSenderTreasury,
            final boolean isReceiverTreasury,
            final WritableNftStore nftStore,
            final WritableAccountStore accountStore) {
        // If sender is not treasury, remove this NftId from the list of sender's NftIds
        if (!isSenderTreasury) {
            removeFromList(nftId, nftStore, from, accountStore);
        }
        // If receiver is not treasury, add this NftId to the list of receiver's NftIds
        if (!isReceiverTreasury) {
            insertToList(nftId, nftStore, to, accountStore);
        } else {
            // If receiver is treasury, remove the previous and next pointers as per mono-service
            final var nft = requireNonNull(nftStore.get(nftId));
            final var nftCopy = nft.copyBuilder();
            nftCopy.ownerPreviousNftId((NftID) null);
            nftCopy.ownerNextNftId((NftID) null);
            nftStore.put(nftCopy.build());
        }
    }

    /**
     * Insert the NFT to the head of the list of NFTs owned by the account.
     * @param nftId - NFT id
     * @param nftStore - NFT store
     * @param to - Account
     * @param accountStore - Account store
     */
    private void insertToList(
            @NonNull final NftID nftId,
            @NonNull final WritableNftStore nftStore,
            @NonNull final Account to,
            @NonNull final WritableAccountStore accountStore) {
        final var nft = requireNonNull(nftStore.get(nftId));
        final var nftCopy = nft.copyBuilder();

        if (to.hasHeadNftId()) {
            final var headNft = requireNonNull(nftStore.get(to.headNftIdOrThrow()));
            final var headCopy = headNft.copyBuilder();
            headCopy.ownerPreviousNftId(nftId);
            nftStore.put(headCopy.build());

            nftCopy.ownerNextNftId(to.headNftId());
        } else {
            nftCopy.ownerNextNftId((NftID) null);
        }
        nftCopy.ownerPreviousNftId((NftID) null);

        final var toAccountCopy = to.copyBuilder();
        toAccountCopy.headNftId(nftId);

        nftStore.put(nftCopy.build());
        accountStore.put(toAccountCopy.build());
    }

    /**
     * Remove the NFT from the list of NFTs owned by the account.
     * @param nftId - NFT id
     * @param nftStore - NFT store
     * @param from - Account
     * @param accountStore - Account store
     */
    public static void removeFromList(
            @NonNull final NftID nftId,
            @NonNull final WritableNftStore nftStore,
            @NonNull final Account from,
            @NonNull final WritableAccountStore accountStore) {
        final var nft = requireNonNull(nftStore.get(nftId));

        if (!nft.hasOwnerPreviousNftId()) {
            final var accountCopy = from.copyBuilder();
            accountCopy.headNftId(nft.ownerNextNftId());
            accountStore.put(accountCopy.build());
        } else {
            final var previousNft = requireNonNull(nftStore.get(nft.ownerPreviousNftIdOrThrow()));
            final var previousCopy = previousNft.copyBuilder();
            previousCopy.ownerNextNftId(nft.ownerNextNftId());
            nftStore.put(previousCopy.build());
        }

        if (nft.hasOwnerNextNftId()) {
            final var nextNft = requireNonNull(nftStore.get(nft.ownerNextNftIdOrThrow()));
            final var nextCopy = nextNft.copyBuilder();
            nextCopy.ownerPreviousNftId(nft.ownerPreviousNftId());
            nftStore.put(nextCopy.build());
        }
    }
}
