// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.util;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_PENDING_AIRDROP_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_RECEIVING_NODE_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.PENDING_AIRDROP_ID_REPEATED;
import static com.hedera.node.app.service.token.impl.handlers.BaseTokenHandler.UNLIMITED_AUTOMATIC_ASSOCIATIONS;
import static com.hedera.node.app.service.token.impl.util.TokenHandlerHelper.getIfUsableForAliasedId;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.PendingAirdropId;
import com.hedera.hapi.node.base.PendingAirdropValue;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.AccountPendingAirdrop;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.hapi.node.transaction.PendingAirdropRecord;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableAirdropStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Utility class that provides static methods.
 */
public class AirdropHandlerHelper {
    private static final Long LAST_RESERVED_SYSTEM_ACCOUNT = 1000L;

    /**
     * Given an account store and a list of validated pending airdrop ids, standardizes the pending airdrop ids
     * to use only the {@code 0.0.X} numeric ids for both sender and receiver.
     * @param accountStore the account store to look up aliases in
     * @param airdropIds the list of pending airdrop ids to standardize
     * @param knownExtantIdTypes the set of id types that are known to exist in the system
     * @return a set of standardized pending airdrop ids
     * @throws HandleException with INVALID_PENDING_AIRDROP_ID if any of the pending airdrop ids are invalid
     */
    public static Set<PendingAirdropId> standardizeAirdropIds(
            @NonNull final ReadableAccountStore accountStore,
            @NonNull final ReadableAirdropStore airdropStore,
            @NonNull final List<PendingAirdropId> airdropIds,
            @NonNull final Set<IdType> knownExtantIdTypes) {
        final Set<PendingAirdropId> standardAirdropIds = new LinkedHashSet<>();
        for (final var airdropId : airdropIds) {
            final var sender = knownExtantIdTypes.contains(IdType.SENDER)
                    ? requireNonNull(accountStore.getAliasedAccountById(airdropId.senderIdOrThrow()))
                    : accountStore.getAliasedAccountById(airdropId.senderIdOrElse(AccountID.DEFAULT));
            validateTrue(sender != null, INVALID_PENDING_AIRDROP_ID);
            final var receiver = knownExtantIdTypes.contains(IdType.RECEIVER)
                    ? requireNonNull(accountStore.getAliasedAccountById(airdropId.receiverIdOrThrow()))
                    : accountStore.getAliasedAccountById(airdropId.receiverIdOrElse(AccountID.DEFAULT));
            validateTrue(receiver != null, INVALID_PENDING_AIRDROP_ID);
            // Airdrop ids always have 0.0.X form of both sender and receiver ids
            final var validatedId = airdropId
                    .copyBuilder()
                    .senderId(sender.accountIdOrThrow())
                    .receiverId(receiver.accountIdOrThrow())
                    .build();
            validateTrue(airdropStore.exists(validatedId), INVALID_PENDING_AIRDROP_ID);
            validateFalse(standardAirdropIds.contains(validatedId), PENDING_AIRDROP_ID_REPEATED);
            standardAirdropIds.add(validatedId);
        }
        final var uniqueAirdropIds = Set.copyOf(standardAirdropIds);
        validateTrue(standardAirdropIds.size() == uniqueAirdropIds.size(), PENDING_AIRDROP_ID_REPEATED);
        return standardAirdropIds;
    }

    /**
     * A type of airdrop-involved id to look up in the account store.
     */
    public enum IdType {
        SENDER,
        RECEIVER
    }

    public record FungibleAirdropLists(
            @NonNull List<AccountAmount> transferFungibleAmounts,
            @NonNull List<AccountAmount> pendingFungibleAmounts,
            int transfersNeedingAutoAssociation) {}

    public record NftAirdropLists(
            @NonNull List<NftTransfer> transferNftList,
            @NonNull List<NftTransfer> pendingNftList,
            int transfersNeedingAutoAssociation) {}

    private AirdropHandlerHelper() {
        throw new UnsupportedOperationException("Utility class only");
    }

    /**
     * Checks every {@link AccountAmount} from given transfer list and separate it in to two lists.
     * One containing transfers that should be added to pending airdrop state and the other list - transfers that should
     * be executed. The check is done by account's available auto associations slots and the existence of account-token
     * relation {@link #isPendingAirdrop(HandleContext, Account, TokenRelation)}
     *
     * @param context {@link HandleContext} used to obtain state stores
     * @param tokenId token id
     * @param transfers list of {@link AccountAmount}
     * @return {@link FungibleAirdropLists} a record containing two lists - transfers to be added in pending state and transfers to be executed
     */
    public static FungibleAirdropLists separateFungibleTransfers(
            @NonNull final HandleContext context,
            @NonNull final TokenID tokenId,
            @NonNull final List<AccountAmount> transfers) {
        final var transferFungibleAmounts = new ArrayList<AccountAmount>();
        final var pendingFungibleAmounts = new ArrayList<AccountAmount>();
        final var transfersNeedingAutoAssociation = new LinkedHashSet<AccountID>();

        final var tokenRelStore = context.storeFactory().readableStore(ReadableTokenRelationStore.class);
        final var accountStore = context.storeFactory().readableStore(ReadableAccountStore.class);
        for (final var aa : transfers) {
            final var accountId = aa.accountIDOrElse(AccountID.DEFAULT);
            // Treat a transfer to a missing account as an auto-creation attempt
            if (accountStore.isMissing(accountId)) {
                transferFungibleAmounts.add(aa);
                transfersNeedingAutoAssociation.add(accountId);
                continue;
            }

            final var account =
                    getIfUsableForAliasedId(accountId, accountStore, context.expiryValidator(), INVALID_ACCOUNT_ID);
            final var tokenRel = tokenRelStore.get(accountId, tokenId);
            var isPendingAirdrop = false;
            if (aa.amount() > 0) {
                validateFalse(isSystemAccount(account), INVALID_RECEIVING_NODE_ACCOUNT);
                isPendingAirdrop = isPendingAirdrop(context, account, tokenRel);
            }

            if (isPendingAirdrop) {
                pendingFungibleAmounts.add(aa);
            } else {
                transferFungibleAmounts.add(aa);
                // Any transfer that is with no explicitly associated token will need to be charged $0.1
                // So we charge $0.05 pending airdrop fee and $0.05 is charged in CryptoTransferHandler during
                // auto-association
                if (tokenRel == null) {
                    transfersNeedingAutoAssociation.add(accountId);
                }
            }
        }

        return new FungibleAirdropLists(
                transferFungibleAmounts, pendingFungibleAmounts, transfersNeedingAutoAssociation.size());
    }

    /**
     * Checks every {@link NftTransfer} from given transfer list and separate it in to two lists.
     * One containing transfers that should be added to pending airdrop state and the other list - transfers that should
     * be executed. The check is done by account's available auto associations slots and the existence of account-token
     * relation {@link #isPendingAirdrop(HandleContext, Account, TokenRelation)}
     *
     * @param context context
     * @param tokenId token id
     * @param transfers list of nft transfers
     * @return {@link NftAirdropLists} a record containing two lists - transfers to be added in pending state and transfers to be executed
     */
    public static NftAirdropLists separateNftTransfers(
            HandleContext context, TokenID tokenId, List<NftTransfer> transfers) {
        List<NftTransfer> transferNftList = new ArrayList<>();
        List<NftTransfer> pendingNftList = new ArrayList<>();
        Set<AccountID> transfersNeedingAutoAssociation = new HashSet<>();

        for (final var nftTransfer : transfers) {
            final var tokenRelStore = context.storeFactory().readableStore(ReadableTokenRelationStore.class);
            final var accountStore = context.storeFactory().readableStore(ReadableAccountStore.class);

            final var receiverId = nftTransfer.receiverAccountIDOrElse(AccountID.DEFAULT);
            // Treat a transfer to a missing account as an auto-creation attempt
            if (accountStore.isMissing(receiverId)) {
                transferNftList.add(nftTransfer);
                transfersNeedingAutoAssociation.add(receiverId);
                continue;
            }

            final var account =
                    getIfUsableForAliasedId(receiverId, accountStore, context.expiryValidator(), INVALID_ACCOUNT_ID);
            validateFalse(isSystemAccount(account), INVALID_RECEIVING_NODE_ACCOUNT);
            var tokenRel = tokenRelStore.get(receiverId, tokenId);
            if (isPendingAirdrop(context, account, tokenRel)) {
                pendingNftList.add(nftTransfer);
            } else {
                transferNftList.add(nftTransfer);
                // Any transfer that is with no explicitly associated token will need to be charged $0.1
                // So we charge $0.05 pending airdrop fee and $0.05 is charged in CryptoTransferHandler during
                // auto-association
                if (tokenRel == null) {
                    transfersNeedingAutoAssociation.add(receiverId);
                }
            }
        }
        return new NftAirdropLists(transferNftList, pendingNftList, transfersNeedingAutoAssociation.size());
    }

    /**
     * Checks if the airdrop to an account is pending airdrop. An airdrop will result into a pending airdrop, iuf
     * the receiver has not signed the transaction when receiverSigRequired is set to true, or if the
     * receiver is no yet associated to teh token but there are no open slots available. In other scenarios,
     * the airdrop is not pending and will be transferred.
     * @param context the context
     * @param receiver the receiver account
     * @param tokenRelation the token relation
     * @return boolean value indicating if the airdrop is pending
     */
    private static boolean isPendingAirdrop(
            @NonNull final HandleContext context,
            @NonNull final Account receiver,
            @Nullable final TokenRelation tokenRelation) {
        // Check if the receiver has signed the transaction. If not, it should result in a pending airdrop
        if (receiver.receiverSigRequired()) {
            var sigVerification = context.keyVerifier().verificationFor(requireNonNull(receiver.key()));
            if (sigVerification.failed()) {
                return true;
            }
        }
        return tokenRelation == null && isAutoAssociationLimitReached(receiver);
    }

    /**
     * Creates a {@link PendingAirdropId} for a fungible token.
     *
     * @param tokenId the ID of the token
     * @param sender the sender's account
     * @param receiver the receiver's account
     * @return {@link PendingAirdropId} for storing in the state
     */
    public static PendingAirdropId createFungibleTokenPendingAirdropId(
            TokenID tokenId, Account sender, Account receiver) {
        return PendingAirdropId.newBuilder()
                .receiverId(receiver.accountIdOrThrow())
                .senderId(sender.accountIdOrThrow())
                .fungibleTokenType(tokenId)
                .build();
    }

    /**
     * Creates a {@link PendingAirdropId} for a fungible token.
     *
     * @param tokenId the ID of the token
     * @param senderId the sender's account ID
     * @param receiverId the receiver's account ID
     * @return {@link PendingAirdropId} for storing in the state
     */
    public static PendingAirdropId createFungibleTokenPendingAirdropId(
            TokenID tokenId, AccountID senderId, AccountID receiverId) {
        return PendingAirdropId.newBuilder()
                .receiverId(receiverId)
                .senderId(senderId)
                .fungibleTokenType(tokenId)
                .build();
    }

    /**
     * Creates a {@link PendingAirdropId} for a non-fungible token.
     *
     * @param tokenId the ID of the token
     * @param serialNumber the serial number of the token
     * @param sender the sender's account
     * @param receiver the receiver's account
     * @return {@link PendingAirdropId} for storing in the state
     */
    public static PendingAirdropId createNftPendingAirdropId(
            TokenID tokenId, long serialNumber, Account sender, Account receiver) {
        var nftId =
                NftID.newBuilder().tokenId(tokenId).serialNumber(serialNumber).build();
        return PendingAirdropId.newBuilder()
                .receiverId(receiver.accountIdOrThrow())
                .senderId(sender.accountIdOrThrow())
                .nonFungibleToken(nftId)
                .build();
    }

    /**
     * Creates a {@link AccountPendingAirdrop} for a fungible token.
     *
     * @param pendingAirdropValue the amount of fungible token
     * @return {@link AccountPendingAirdrop} for storing in the state
     */
    public static AccountPendingAirdrop createFirstAccountPendingAirdrop(PendingAirdropValue pendingAirdropValue) {
        return createAccountPendingAirdrop(pendingAirdropValue, null);
    }

    /**
     * Creates a {@link AccountPendingAirdrop} for a fungible token.
     *
     * @param pendingAirdropValue the amount of fungible token
     * @return {@link AccountPendingAirdrop} for storing in the state
     */
    public static AccountPendingAirdrop createAccountPendingAirdrop(
            @Nullable final PendingAirdropValue pendingAirdropValue, @Nullable final PendingAirdropId next) {
        return AccountPendingAirdrop.newBuilder()
                .pendingAirdropValue(pendingAirdropValue)
                .nextAirdrop(next)
                .build();
    }

    /**
     * Creates a {@link PendingAirdropRecord} for externalizing pending airdrop records.
     *
     * @param pendingAirdropId the ID of the pending airdrop
     * @param pendingAirdropValue the value of the pending airdrop
     * @return {@link PendingAirdropRecord}
     */
    public static PendingAirdropRecord createPendingAirdropRecord(
            @NonNull final PendingAirdropId pendingAirdropId, @Nullable final PendingAirdropValue pendingAirdropValue) {
        return PendingAirdropRecord.newBuilder()
                .pendingAirdropId(pendingAirdropId)
                .pendingAirdropValue(pendingAirdropValue)
                .build();
    }

    /**
     * Returns if given account is a system account.
     *
     * @param account the account that need to be validated
     * @return boolean value indicating if the account is a system account
     */
    public static boolean isSystemAccount(@NonNull Account account) {
        requireNonNull(account);
        return account.accountIdOrThrow().accountNumOrThrow() <= LAST_RESERVED_SYSTEM_ACCOUNT;
    }

    private static boolean isAutoAssociationLimitReached(@NonNull final Account receiver) {
        return receiver.maxAutoAssociations() <= receiver.usedAutoAssociations()
                && receiver.maxAutoAssociations() != UNLIMITED_AUTOMATIC_ASSOCIATIONS;
    }
}
