// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl;

import static com.hedera.hapi.node.base.ResponseCodeEnum.FAIL_INVALID;
import static com.hedera.hapi.node.base.TokenType.NON_FUNGIBLE_UNIQUE;
import static com.hedera.node.app.service.token.impl.comparator.TokenComparators.ACCOUNT_AMOUNT_COMPARATOR;
import static com.hedera.node.app.service.token.impl.comparator.TokenComparators.NFT_TRANSFER_COMPARATOR;
import static com.hedera.node.app.service.token.impl.handlers.staking.StakingRewardsHelper.asAccountAmounts;
import static com.hedera.node.app.service.token.impl.handlers.transfer.customfees.AdjustmentUtils.addExactOrThrowReason;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.state.common.EntityIDPair;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.node.app.service.token.impl.handlers.staking.StakingRewardsHelper.StakingAnalysisScratch;
import com.hedera.node.app.spi.workflows.HandleException;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public abstract class RecordFinalizerBase {
    protected static final AccountID ZERO_ACCOUNT_ID =
            AccountID.newBuilder().accountNum(0).build();
    private final ThreadLocal<ScratchHolder> scratchHolder = ThreadLocal.withInitial(ScratchHolder::new);

    /**
     * Acquires scratch collections for one finalization. A nested finalization receives a distinct buffer, while a
     * later finalization on the same thread reuses the cleared buffer at the same depth.
     */
    protected final ScratchLease acquireScratch() {
        return scratchHolder.get().acquire();
    }

    /**
     * Whether system entities are being created (which in particular requires non-zero sum
     * hbar balance changes).
     */
    protected abstract boolean systemEntitiesCreated();

    /**
     * Gets all hbar changes for all modified accounts from the given {@link WritableAccountStore}.
     *
     * @param writableAccountStore the {@link WritableAccountStore} to get the hbar changes from
     * @param maxLegalBalance the max legal balance for any account
     * @return a {@link Map} of {@link AccountID} to {@link Long} representing the hbar changes for all modified
     */
    @NonNull
    protected Map<AccountID, Long> hbarChangesFrom(
            @NonNull final WritableAccountStore writableAccountStore,
            final long maxLegalBalance,
            @NonNull final Map<AccountID, Account> cachedOriginalAccounts,
            @NonNull final Map<AccountID, Long> hbarChanges) {
        final var modifiedAccountIds = writableAccountStore.modifiedAccountsInState();
        var netHbarBalance = 0L;
        for (final AccountID modifiedAcctId : modifiedAccountIds) {
            final var modifiedAcct = requireNonNull(writableAccountStore.getAccountById(modifiedAcctId));
            final var persistedAcct = cachedOriginalAccounts.containsKey(modifiedAcctId)
                    ? cachedOriginalAccounts.get(modifiedAcctId)
                    : writableAccountStore.getOriginalValue(modifiedAcctId);
            // It's possible the modified account was created in this transaction, in which case the non-existent
            // persisted account effectively has no balance (i.e. its prior balance is 0)
            final var persistedBalance = persistedAcct != null ? persistedAcct.tinybarBalance() : 0;

            // Never allow an account's net hbar balance to be negative
            validateTrue(modifiedAcct.tinybarBalance() >= 0, FAIL_INVALID);
            validateTrue(modifiedAcct.tinybarBalance() <= maxLegalBalance, FAIL_INVALID);

            final var netHbarChange = modifiedAcct.tinybarBalance() - persistedBalance;
            if (netHbarChange != 0) {
                netHbarBalance = addExactOrThrowReason(netHbarBalance, netHbarChange, FAIL_INVALID);
                hbarChanges.put(modifiedAcctId, netHbarChange);
            }
        }
        // Net HBAR balances can only change when creating system entities at genesis
        if (netHbarBalance != 0 && systemEntitiesCreated()) {
            throw new HandleException(FAIL_INVALID);
        }

        return hbarChanges;
    }

    public enum IsCryptoTransfer {
        YES,
        NO
    }

    /**
     * Gets all token tokenRelation balances for all modified token relations from the given {@link WritableTokenRelationStore} depending on the given token type.
     *
     * @param writableTokenRelStore the {@link WritableTokenRelationStore} to get the token relation balances from
     * @return a {@link Map} of {@link EntityIDPair} to {@link Long} representing the token relation balances for all
     * modified token relations
     */
    @NonNull
    protected Map<EntityIDPair, Long> tokenRelChangesFrom(
            @NonNull final WritableTokenRelationStore writableTokenRelStore,
            @NonNull final IsCryptoTransfer isCryptoTransfer,
            @NonNull final Map<EntityIDPair, Long> tokenRelChanges) {
        final var modifiedTokenRels = writableTokenRelStore.modifiedTokens();
        for (final EntityIDPair modifiedRel : modifiedTokenRels) {
            final var relAcctId = modifiedRel.accountIdOrThrow();
            final var relTokenId = modifiedRel.tokenIdOrThrow();
            final var modifiedTokenRel = writableTokenRelStore.get(relAcctId, relTokenId);
            final var persistedTokenRel = writableTokenRelStore.getOriginalValue(relAcctId, relTokenId);

            // It's possible the modified token rel was created in this transaction. If so, use a persisted balance of 0
            // for the token rel that didn't exist
            final var persistedBalance = persistedTokenRel != null ? persistedTokenRel.balance() : 0;
            // It is possible that the account is dissociated with the token in this transaction. If so, use a
            // balance of 0 for the token rel that didn't exist
            final var modifiedTokenRelBalance = modifiedTokenRel != null ? modifiedTokenRel.balance() : 0;
            // Never allow a fungible token's balance to be negative
            validateTrue(modifiedTokenRelBalance >= 0, FAIL_INVALID);

            // If the token rel's balance has changed, add it to the list of changes
            final var netFungibleChange = modifiedTokenRelBalance - persistedBalance;
            if (netFungibleChange != 0) {
                tokenRelChanges.put(modifiedRel, netFungibleChange);
            } else {
                // It is possible that we have updated pointers in the token relation during crypto transfer
                // but the balance has not changed.Since we don't filter zero amounts in crypto transfer, we need to
                // check they
                // are not just pointer changes to avoid adding them to record
                // We don't check next pointer here, because the next pointer can change during associate or
                // dissociate, and we filter zero amounts for those records
                final var prevPointerChanged = !Objects.equals(
                        persistedTokenRel != null ? persistedTokenRel.previousToken() : null,
                        modifiedTokenRel != null ? modifiedTokenRel.previousToken() : null);
                if (isCryptoTransfer == IsCryptoTransfer.YES && !prevPointerChanged) {
                    tokenRelChanges.put(modifiedRel, 0L);
                }
            }
        }

        return tokenRelChanges;
    }

    /**
     * Given a map of {@link EntityIDPair} to {@link Long} representing the changes to the balances of the token
     * relations, returns a list of {@link TokenTransferList} representing the changes to the token relations.
     *
     * @param fungibleChanges the map of {@link EntityIDPair} to {@link Long} representing the changes to the balances
     * @param isCryptoTransfer the {@link IsCryptoTransfer} representing if the transaction is a crypto transfer
     * @return a list of {@link TokenTransferList} representing the changes to the token relations
     */
    @NonNull
    protected List<TokenTransferList> asTokenTransferListFrom(
            @NonNull final Map<EntityIDPair, Long> fungibleChanges,
            @NonNull final IsCryptoTransfer isCryptoTransfer,
            @NonNull final FinalizationScratch scratch) {
        final var acctAmountsByTokenId = scratch.acctAmountsByTokenId;
        for (final var fungibleChange : fungibleChanges.entrySet()) {
            final var tokenIdOfAcctAmountChange = fungibleChange.getKey().tokenId();
            final var accountIdOfAcctAmountChange = fungibleChange.getKey().accountId();
            if (!acctAmountsByTokenId.containsKey(tokenIdOfAcctAmountChange)) {
                acctAmountsByTokenId.put(tokenIdOfAcctAmountChange, scratch.nextAccountAmounts());
            }
            if (fungibleChange.getValue() != 0 || isCryptoTransfer == IsCryptoTransfer.YES) {
                final var tokenIdMap = acctAmountsByTokenId.get(tokenIdOfAcctAmountChange);
                tokenIdMap.merge(accountIdOfAcctAmountChange, fungibleChange.getValue(), Long::sum);
            }
        }
        final var fungibleTokenTransferLists = new ArrayList<TokenTransferList>(acctAmountsByTokenId.size());
        // Mold the fungible changes into a transfer ordered by (token ID, account ID). The fungible pairs are ordered
        // by (accountId, tokenId), so we need to group by each token ID
        for (final var acctAmountsForToken : acctAmountsByTokenId.entrySet()) {
            final var singleTokenTransfers = acctAmountsForToken.getValue();
            if (!singleTokenTransfers.isEmpty()) {
                final var aaList = asAccountAmounts(singleTokenTransfers);
                aaList.sort(ACCOUNT_AMOUNT_COMPARATOR);
                if (isCryptoTransfer == IsCryptoTransfer.YES) {
                    long netAdjustment = 0L;
                    for (final var aa : aaList) {
                        netAdjustment = addExactOrThrowReason(netAdjustment, aa.amount(), FAIL_INVALID);
                    }
                    if (netAdjustment != 0L) {
                        throw new HandleException(FAIL_INVALID);
                    }
                }
                fungibleTokenTransferLists.add(TokenTransferList.newBuilder()
                        .token(acctAmountsForToken.getKey())
                        .transfers(List.copyOf(aaList))
                        .build());
            }
        }

        return fungibleTokenTransferLists;
    }

    /**
     * Gets all nft ownership changes for all modified nfts from the given {@link WritableNftStore}.
     * While building the nft changes, we also update the token relation changes. If there are any token relation
     * changes for the sender and receiver of the NFTs, we reduce the balance change for that relation by 1 for the
     * receiver and increment the balance change for sender by 1. This is to ensure that the NFT transfer is not double
     * counted in the token relation changes and the NFT changes.
     * We also update the token relation changes for the treasury account when the treasury account changes.
     *
     * @param writableNftStore the {@link WritableNftStore} to get the nft ownership changes from
     * @param tokenRelChanges the {@link Map} of {@link EntityIDPair} to {@link Long} representing the token relation
     * @return a {@link Map} of {@link TokenID} to {@link List} of {@link NftTransfer} representing the nft ownership
     */
    @NonNull
    protected Map<TokenID, List<NftTransfer>> nftChangesFrom(
            @NonNull final WritableNftStore writableNftStore,
            @NonNull final WritableTokenStore writableTokenStore,
            final Map<EntityIDPair, Long> tokenRelChanges,
            @NonNull final FinalizationScratch scratch) {
        final var modifiedNftIds = writableNftStore.modifiedNfts();
        final var modifiedTokenIds = writableTokenStore.modifiedTokens();
        final var nftChanges = scratch.nftChanges;
        for (final NftID nftId : modifiedNftIds) {
            final var modifiedNft = writableNftStore.get(nftId);
            final var persistedNft = writableNftStore.getOriginalValue(nftId);

            // The NFT may not have existed before, in which case we'll use a null sender account ID
            AccountID senderAccountId;
            if (persistedNft != null) {
                // If the NFT did not have an owner before set it to the treasury account
                if (persistedNft.hasOwnerId()) {
                    senderAccountId = persistedNft.ownerIdOrThrow();
                } else {
                    final var tokenId = nftId.tokenId();
                    requireNonNull(tokenId);
                    final var token = writableTokenStore.get(tokenId);
                    requireNonNull(token);
                    senderAccountId = token.treasuryAccountIdOrThrow();
                }
            } else {
                senderAccountId = ZERO_ACCOUNT_ID;
            }

            // If the NFT has been burned or wiped, modifiedNft will be null. In that case the receiverId
            // will be explicitly set as 0.0.0
            AccountID receiverAccountId;
            if (modifiedNft != null) {
                if (modifiedNft.hasOwnerId()) {
                    receiverAccountId = modifiedNft.ownerIdOrThrow();
                } else {
                    final var tokenId = nftId.tokenId();
                    requireNonNull(tokenId);
                    final var token = writableTokenStore.get(tokenId);
                    requireNonNull(token);
                    receiverAccountId = token.treasuryAccountIdOrThrow();
                }
            } else {
                receiverAccountId = ZERO_ACCOUNT_ID;
            }
            // If both sender and receiver are same it is not a transfer
            if (receiverAccountId.equals(senderAccountId)) {
                continue;
            }
            updateNftChanges(nftId, senderAccountId, receiverAccountId, nftChanges, tokenRelChanges, scratch);
        }

        for (final var tokenId : modifiedTokenIds) {
            final var originalToken = writableTokenStore.getOriginalValue(tokenId);
            final var modifiedToken = requireNonNull(writableTokenStore.get(tokenId));
            if (bothExistButTreasuryChanged(originalToken, modifiedToken)
                    && originalToken.tokenType() == NON_FUNGIBLE_UNIQUE) {
                // When the treasury account changes, all the treasury-owned NFTs are in a sense
                // "transferred" to the new treasury; but we cannot list all these transfers in the record,
                // so instead we put a sentinel NFT transfer with serial number -1 to trigger mirror
                // nodes to update their owned NFT counts
                updateNftChanges(
                        NftID.newBuilder().tokenId(tokenId).serialNumber(-1).build(),
                        originalToken.treasuryAccountId(),
                        modifiedToken.treasuryAccountId(),
                        nftChanges,
                        tokenRelChanges,
                        scratch);
            }
        }
        return nftChanges;
    }

    /**
     * Checks if both the original token and modified token exist and the treasury account ID has changed.
     * @param originalToken the {@link Token} representing the original token
     * @param modifiedToken the {@link Token} representing the modified token
     * @return true if both the original token and modified token exist and the treasury account ID has changed
     */
    private static boolean bothExistButTreasuryChanged(
            @Nullable final Token originalToken, @NonNull final Token modifiedToken) {
        return originalToken != null
                && !originalToken.treasuryAccountId().equals(modifiedToken.treasuryAccountIdOrElse(AccountID.DEFAULT));
    }

    /**
     * Updates the given {@link Map} of {@link TokenID} to {@link List} of {@link NftTransfer} representing the nft
     * ownership changes.
     * While building the nft changes, we also update the token relation changes. If there are any token relation
     * changes for the sender and receiver of the NFTs, we reduce the balance change for that relation by 1 for the
     * receiver and increment the balance change for sender by 1. This is to ensure that the NFT transfer is not double
     * counted in the token relation changes and the NFT changes.
     * We also update the token relation changes for the treasury account when the treasury account changes.
     *
     * @param nftId             the {@link NftID} representing the nft
     * @param senderAccountId   the {@link AccountID} representing the sender account ID
     * @param receiverAccountId the {@link AccountID} representing the receiver account ID
     * @param nftChanges        the {@link Map} of {@link TokenID} to {@link List} of {@link NftTransfer} representing the nft
     * @param tokenRelChanges  the {@link Map} of {@link EntityIDPair} to {@link Long} representing the token relation
     */
    private static void updateNftChanges(
            final NftID nftId,
            final AccountID senderAccountId,
            final AccountID receiverAccountId,
            final Map<TokenID, List<NftTransfer>> nftChanges,
            @Nullable final Map<EntityIDPair, Long> tokenRelChanges,
            @NonNull final FinalizationScratch scratch) {
        final var isMint = senderAccountId.accountNum() == 0;
        final var isWipeOrBurn = receiverAccountId.accountNum() == 0;
        final var isTreasuryChange = nftId.serialNumber() == -1;
        final var nftTransfer = NftTransfer.newBuilder()
                .serialNumber(nftId.serialNumber())
                .senderAccountID(senderAccountId)
                .receiverAccountID(receiverAccountId)
                .build();

        if (!nftChanges.containsKey(nftId.tokenId())) {
            nftChanges.put(nftId.tokenId(), scratch.nextNftTransfers());
        }

        final var currentNftChanges = nftChanges.get(nftId.tokenId());
        currentNftChanges.add(nftTransfer);
        nftChanges.put(nftId.tokenId(), currentNftChanges);

        if (!tokenRelChanges.isEmpty()) {
            final var receiverEntityIdPair = EntityIDPair.newBuilder()
                    .accountId(receiverAccountId)
                    .tokenId(nftId.tokenId())
                    .build();
            final var senderEntityIdPair = EntityIDPair.newBuilder()
                    .accountId(senderAccountId)
                    .tokenId(nftId.tokenId())
                    .build();
            if (isMint || isWipeOrBurn || isTreasuryChange) {
                // The mint amount is not shown in the token transfer list. So for a mint transaction we need not show
                // the token transfer changes to treasury. If it is a treasury change, we need not show the transfer
                // changes to treasury
                tokenRelChanges.remove(receiverEntityIdPair);
                tokenRelChanges.remove(senderEntityIdPair);
            } else {
                // Because the NFT transfer list already contains all information about how the
                // sender/receiver # of owned NFT counts are changing, we don't repeat this in
                // the tokenTransferLists; these merge() calls remove the duplicate information
                if (tokenRelChanges.merge(receiverEntityIdPair, -1L, Long::sum) == 0) {
                    tokenRelChanges.remove(receiverEntityIdPair);
                }
                // We don't need to show the mint amounts in token transfer List
                if (tokenRelChanges.merge(senderEntityIdPair, 1L, Long::sum) == 0) {
                    tokenRelChanges.remove(senderEntityIdPair);
                }
            }
        }
    }

    /**
     * Given a {@link Map} of {@link TokenID} to {@link List} of {@link NftTransfer} representing the nft ownership
     * changes, returns a list of {@link TokenTransferList} representing the changes to the nft ownership.
     * @param nftChanges the {@link Map} of {@link TokenID} to {@link List} of {@link NftTransfer} representing the nft
     * @return a list of {@link TokenTransferList} representing the changes to the nft ownership
     */
    protected List<TokenTransferList> asTokenTransferListFromNftChanges(
            final Map<TokenID, List<NftTransfer>> nftChanges) {

        // Create a new transfer list for each token ID
        final var nftTokenTransferLists = new ArrayList<TokenTransferList>(nftChanges.size());
        for (final var nftsForTokenId : nftChanges.entrySet()) {
            if (!nftsForTokenId.getValue().isEmpty()) {
                // This var is the collection of all NFT transfers _for a single token ID_
                // NFT serial numbers will be sorted to match mono behavior
                final var nftTransfersForTokenId = nftsForTokenId.getValue();
                nftTransfersForTokenId.sort(NFT_TRANSFER_COMPARATOR);
                nftTokenTransferLists.add(TokenTransferList.newBuilder()
                        .token(nftsForTokenId.getKey())
                        .nftTransfers(List.copyOf(nftTransfersForTokenId))
                        .build());
            }
        }

        return nftTokenTransferLists;
    }

    protected final class ScratchLease implements AutoCloseable {
        private final ScratchHolder owner;
        private final int depth;
        private final FinalizationScratch scratch;
        private boolean closed;

        private ScratchLease(final ScratchHolder owner, final int depth, final FinalizationScratch scratch) {
            this.owner = owner;
            this.depth = depth;
            this.scratch = scratch;
        }

        public FinalizationScratch scratch() {
            return scratch;
        }

        @Override
        public void close() {
            if (!closed) {
                owner.release(depth);
                closed = true;
            }
        }
    }

    protected static final class FinalizationScratch {
        private final Map<AccountID, Long> hbarChanges = new HashMap<>();
        private final Map<EntityIDPair, Long> tokenRelChanges = new HashMap<>();
        private final Map<TokenID, List<NftTransfer>> nftChanges = new HashMap<>();
        private final Map<TokenID, HashMap<AccountID, Long>> acctAmountsByTokenId = new HashMap<>();
        private final List<HashMap<AccountID, Long>> accountAmountMaps = new ArrayList<>();
        private final List<ArrayList<NftTransfer>> nftTransferLists = new ArrayList<>();
        private final Map<NftID, AccountID> childFinalNftOwners = new HashMap<>();
        private final StakingAnalysisScratch stakingScratch = new StakingAnalysisScratch();
        private int accountAmountMapCount;
        private int nftTransferListCount;

        public StakingAnalysisScratch stakingScratch() {
            return stakingScratch;
        }

        public Map<AccountID, Long> hbarChanges() {
            return hbarChanges;
        }

        public Map<EntityIDPair, Long> tokenRelChanges() {
            return tokenRelChanges;
        }

        public Map<TokenID, List<NftTransfer>> nftChanges() {
            return nftChanges;
        }

        public Map<NftID, AccountID> childFinalNftOwners() {
            return childFinalNftOwners;
        }

        private HashMap<AccountID, Long> nextAccountAmounts() {
            if (accountAmountMapCount == accountAmountMaps.size()) {
                accountAmountMaps.add(new HashMap<>());
            }
            return accountAmountMaps.get(accountAmountMapCount++);
        }

        private ArrayList<NftTransfer> nextNftTransfers() {
            if (nftTransferListCount == nftTransferLists.size()) {
                nftTransferLists.add(new ArrayList<>());
            }
            return nftTransferLists.get(nftTransferListCount++);
        }

        private void clear() {
            hbarChanges.clear();
            tokenRelChanges.clear();
            nftChanges.clear();
            acctAmountsByTokenId.clear();
            childFinalNftOwners.clear();
            accountAmountMapCount = 0;
            nftTransferListCount = 0;
            accountAmountMaps.forEach(Map::clear);
            nftTransferLists.forEach(List::clear);
            stakingScratch.reset();
        }
    }

    private final class ScratchHolder {
        private final List<FinalizationScratch> buffers = new ArrayList<>();
        private int depth;

        private ScratchLease acquire() {
            if (depth == buffers.size()) {
                buffers.add(new FinalizationScratch());
            }
            final var scratch = buffers.get(depth);
            scratch.clear();
            return new ScratchLease(this, depth++, scratch);
        }

        private void release(final int acquiredDepth) {
            if (acquiredDepth != depth - 1) {
                throw new IllegalStateException("Finalization scratch released out of order");
            }
            depth--;
        }
    }
}
