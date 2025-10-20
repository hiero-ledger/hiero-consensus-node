// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl;

import static com.hedera.hapi.node.base.AccountID.AccountOneOfType.ACCOUNT_NUM;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INDIRECTION_KEY_TARGET_NOT_FOUND;
import static com.hedera.node.app.service.token.AliasUtils.asKeyFromAlias;
import static com.hedera.node.app.service.token.AliasUtils.extractEvmAddress;
import static com.hedera.node.app.service.token.AliasUtils.isAlias;
import static com.hedera.node.app.service.token.AliasUtils.isOfEvmAddressSize;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.contract.ContractNonceInfo;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.IndirectKeyUsersKey;
import com.hedera.hapi.node.state.token.IndirectKeyUsersValue;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.hapi.utils.keys.KeyUtils;
import com.hedera.node.app.service.token.api.ContractChangeSummary;
import com.hedera.node.app.spi.ids.WritableEntityCounters;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides write methods for modifying underlying data storage mechanisms for working with
 * accounts.
 *
 * <p>This class is not exported from the module. It is an internal implementation detail. This
 * class is not complete, it will be extended with other methods like remove, update etc.,
 */
public class WritableAccountStore extends ReadableAccountStoreImpl {
    private static final Logger log = LoggerFactory.getLogger(WritableAccountStore.class);
    private final WritableEntityCounters entityCounters;

    public WritableAccountStore(
            @NonNull final WritableStates states, @NonNull final WritableEntityCounters entityCounters) {
        super(states, entityCounters);
        this.entityCounters = entityCounters;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected WritableKVState<AccountID, Account> accountState() {
        return super.accountState();
    }

    @Override
    @SuppressWarnings("unchecked")
    public WritableKVState<IndirectKeyUsersKey, IndirectKeyUsersValue> indirectKeyUsers() {
        return super.indirectKeyUsers();
    }

    @Override
    @SuppressWarnings("unchecked")
    protected WritableKVState<ProtoBytes, AccountID> aliases() {
        return super.aliases();
    }

    /**
     * Inserts the given user into the indirect key users list for the given key account.
     * @param keyAccountId the key account id
     * @param userId the user id
     */
    public void insertIndirectUser(@NonNull final AccountID keyAccountId, @NonNull final AccountID userId) {
        if (userId.account().kind() != ACCOUNT_NUM) {
            throw new IllegalArgumentException("User account id is not numbered - " + userId);
        }
        final var keyAccount = get(keyAccountId);
        validateTrue(keyAccount != null, INDIRECTION_KEY_TARGET_NOT_FOUND);
        final var keyAccountBuilder = keyAccount.copyBuilder();
        final var nextInLineId = keyAccount.nextInLineKeyUserId();
        if (nextInLineId != null && !AccountID.DEFAULT.equals(nextInLineId)) {
            // Insert after next in line
            final var prevKey = IndirectKeyUsersKey.newBuilder()
                    .keyAccountId(keyAccountId)
                    .indirectUserId(nextInLineId)
                    .build();
            final var prevVal = requireNonNull(indirectKeyUsers().get(prevKey));
            final var nextId = prevVal.nextUserId();
            // New entry
            final var newVal = IndirectKeyUsersValue.newBuilder()
                    .prevUserId(nextInLineId)
                    .nextUserId(nextId)
                    .build();
            final var newKey = IndirectKeyUsersKey.newBuilder()
                    .keyAccountId(keyAccountId)
                    .indirectUserId(userId)
                    .build();
            indirectKeyUsers().put(newKey, newVal);
            // Patch 'after' next
            indirectKeyUsers()
                    .put(prevKey, prevVal.copyBuilder().nextUserId(userId).build());
            // Patch 'afterNext' prev
            if (nextId != null && !AccountID.DEFAULT.equals(nextId)) {
                final var nextKey = IndirectKeyUsersKey.newBuilder()
                        .keyAccountId(keyAccountId)
                        .indirectUserId(nextId)
                        .build();
                final var afterNextVal = requireNonNull(indirectKeyUsers().get(nextKey));
                indirectKeyUsers()
                        .put(
                                nextKey,
                                afterNextVal.copyBuilder().prevUserId(userId).build());
            }
        } else {
            // Insert at head (before first)
            final var oldRootId = keyAccountBuilder.build().firstKeyUserId();
            final var newRootKey = IndirectKeyUsersKey.newBuilder()
                    .keyAccountId(keyAccountId)
                    .indirectUserId(userId)
                    .build();
            final var newRootVal = IndirectKeyUsersValue.newBuilder()
                    .prevUserId((AccountID) null)
                    .nextUserId(oldRootId)
                    .build();
            indirectKeyUsers().put(newRootKey, newRootVal);
            if (oldRootId != null && !AccountID.DEFAULT.equals(oldRootId)) {
                final var oldHeadKey = IndirectKeyUsersKey.newBuilder()
                        .keyAccountId(keyAccountId)
                        .indirectUserId(oldRootId)
                        .build();
                final var oldHeadVal = requireNonNull(indirectKeyUsers().get(oldHeadKey));
                indirectKeyUsers()
                        .put(
                                oldHeadKey,
                                oldHeadVal.copyBuilder().prevUserId(userId).build());
            }
            keyAccountBuilder.firstKeyUserId(userId);
        }
        incrementIndirectUserCount(keyAccountBuilder);
        put(keyAccountBuilder.build());
    }

    /**
     * Unlinks this account from any other accounts that it indirectly references via its key.
     * @param account the account to unlink
     */
    public void cleanupIndirectKeyUsagesForDeleted(@NonNull final Account account) {
        requireNonNull(account);
        final var refIds = KeyUtils.getIndirectAccountRefs(account.keyOrElse(Key.DEFAULT));
        final var deletedId = account.accountIdOrThrow();
        for (final var refId : refIds) {
            removeIndirectUser(refId, deletedId);
        }
    }

    /**
     * Removes the given user from the indirect key users list for the given key account.
     * @param keyAccountId the key account id
     * @param userId the user id
     */
    public void removeIndirectUser(@NonNull final AccountID keyAccountId, @NonNull final AccountID userId) {
        final var usedAccount = get(keyAccountId);
        if (usedAccount == null) {
            log.warn("Account {} indirection pointed at missing {}", userId, keyAccountId);
            return;
        }
        final var builder = usedAccount.copyBuilder();
        // If target has an in-progress propagation, and the next-in-line user id is
        // the removed user id, advance the target's next in-line (wrapping to its
        // first indirect user if this is end)
        final var nextInLine = usedAccount.nextInLineKeyUserId();
        if (nextInLine != null && nextInLine.equals(userId)) {
            final var advancedId =
                    nextUserIdInList(indirectKeyUsers(), keyAccountId, userId, usedAccount.firstKeyUserIdOrThrow());
            if (Objects.equals(userId, advancedId)) {
                // This was the only indirect user; so there is no next in line,
                // and it's safe to zero out max remaining propagations in target
                builder.nextInLineKeyUserId((AccountID) null).maxRemainingPropagations(0);
            } else {
                builder.nextInLineKeyUserId(advancedId);
            }
        }
        // Remove (targetId, deletedId) from state and fix pointers
        removeIndirectUserFromList(indirectKeyUsers(), builder, keyAccountId, userId);
        decrementIndirectUserCount(builder);
        put(builder.build());
    }

    /**
     * Persists an updated {@link Account} into the state. If an account with the same ID already exists, it will be overwritten.
     *
     * @param account - the account to be added to modifications in state.
     */
    public void put(@NonNull final Account account) {
        Objects.requireNonNull(account);
        requireNotDefault(account.accountIdOrThrow());
        accountState().put(account.accountIdOrThrow(), account);
    }

    /**
     * Persists a new {@link Account} into the state. Also increments the entity count for {@link EntityType#ACCOUNT}.
     * @param account - the account to be added in state.
     */
    public void putAndIncrementCount(@NonNull final Account account) {
        put(account);
        entityCounters.incrementEntityTypeCount(EntityType.ACCOUNT);
    }

    /**
     * Persists a new alias linked to the account persisted to state.
     *
     * @param alias     - the alias to be added to modifications in state.
     * @param accountId - the account number to be added to modifications in state.
     */
    public void putAlias(@NonNull final Bytes alias, final AccountID accountId) {
        requireNonNull(alias);
        requireNotDefault(alias);
        requireNotDefault(accountId);

        // The specified account ID must always have an account number, and not an alias. If it doesn't have
        // an account number, or if it has both an account number and alias, then we are going to throw an
        // exception. That should never happen.
        if (isAlias(accountId)) {
            throw new IllegalArgumentException("The account ID used with putAlias must have a number and not an alias");
        }

        // We should *never* see an empty alias. If we do, it is problem with the code.
        if (alias.length() == 0) {
            throw new IllegalArgumentException("Alias cannot be empty");
        }

        aliases().put(new ProtoBytes(alias), accountId);
    }

    /**
     * Persists a new alias linked to the account persisted to state. Also increments the entity count for {@link EntityType#ALIAS}.
     * @param alias    - the alias to be added in state.
     * @param accountId - the account number to be added in state.
     */
    public void putAndIncrementCountAlias(@NonNull final Bytes alias, final AccountID accountId) {
        putAlias(alias, accountId);
        entityCounters.incrementEntityTypeCount(EntityType.ALIAS);
    }

    /**
     * Removes an alias from the cache. This should only ever happen as the result of a delete operation.
     *
     * @param alias The alias of the account to remove.
     */
    public void removeAlias(@NonNull final Bytes alias) {
        requireNonNull(alias);
        // FUTURE: We explicitly set alias to Bytes.EMPTY when deleting Contract. So cannot assert it cannot be default.
        // Need to validate if that is correct behavior.
        // We really shouldn't ever see an empty alias. But, if we do, we don't want to do any additional work.
        // FUTURE: It might be worth adding a log statement here if we see an empty alias, but maybe not.
        if (alias.length() > 0) {
            if (!isOfEvmAddressSize(alias)) {
                final var key = asKeyFromAlias(alias);
                final var evmAddress = extractEvmAddress(key);
                if (evmAddress != null) {
                    aliases().remove(new ProtoBytes(evmAddress));
                    entityCounters.decrementEntityTypeCounter(EntityType.ALIAS);
                }
            }
            aliases().remove(new ProtoBytes(alias));
            entityCounters.decrementEntityTypeCounter(EntityType.ALIAS);
        }
    }

    /**
     * Returns the {@link Account} with the given number. If no such account exists, returns {@code
     * null}
     *
     * @param accountID - the id of the Account to be retrieved.
     * @return the Account with the given AccountID, or null if no such account exists
     */
    @Nullable
    public Account get(@NonNull final AccountID accountID) {
        return getAccountLeaf(requireNonNull(accountID));
    }

    /**
     * Gets the original value associated with the given accountId before any modifications were made to
     * it. The returned value will be {@code null} if the accountId does not exist.
     *
     * @param id The accountId. Cannot be null, otherwise an exception is thrown.
     * @return The original value, or null if there is no such accountId in the state
     * @throws NullPointerException if the accountId is null.
     */
    @Nullable
    public Account getOriginalValue(@NonNull final AccountID id) {
        requireNonNull(id);
        // Get the account number based on the account identifier. It may be null.
        final var accountId = id.account().kind() == ACCOUNT_NUM ? id : null;
        return accountId == null ? null : accountState().getOriginalValue(accountId);
    }

    /**
     * Returns the set of accounts modified in existing state.
     *
     * @return the set of accounts modified in existing state
     */
    @NonNull
    public Set<AccountID> modifiedAccountsInState() {
        return accountState().modifiedKeys();
    }

    /**
     * Returns a summary of the changes made to contract state.
     *
     * @return a summary of the changes made to contract state
     */
    public @NonNull ContractChangeSummary summarizeContractChanges() {
        final List<ContractID> newContractIds = new ArrayList<>();
        final List<ContractNonceInfo> updates = new ArrayList<>();
        accountState().modifiedKeys().forEach(accountId -> {
            final var newAccount = accountState().get(accountId);
            if (newAccount != null && newAccount.smartContract()) {
                final var oldAccount = accountState().getOriginalValue(accountId);
                if (oldAccount == null
                        || !oldAccount.smartContract()
                        || oldAccount.ethereumNonce() != newAccount.ethereumNonce()) {
                    final var contractId = ContractID.newBuilder()
                            .shardNum(accountId.shardNum())
                            .realmNum(accountId.realmNum())
                            .contractNum(accountId.accountNumOrThrow())
                            .build();
                    // exclude nonce info if contract was destructed
                    if (!newAccount.deleted()) {
                        updates.add(new ContractNonceInfo(contractId, newAccount.ethereumNonce()));
                    }
                    if (oldAccount == null || !oldAccount.smartContract()) {
                        newContractIds.add(contractId);
                    }
                }
            }
        });
        return new ContractChangeSummary(newContractIds, updates);
    }

    /**
     * Returns the set of aliases modified in existing state.
     *
     * @return the set of aliases modified in existing state
     */
    @NonNull
    public Set<ProtoBytes> modifiedAliasesInState() {
        return aliases().modifiedKeys();
    }

    /**
     * Checks if the given accountId is not the default accountId. If it is, throws an {@link IllegalArgumentException}.
     *
     * @param accountId The accountId to check.
     */
    public static void requireNotDefault(@NonNull final AccountID accountId) {
        if (accountId.equals(AccountID.DEFAULT)) {
            throw new IllegalArgumentException("Account ID cannot be default");
        }
    }

    private static void removeIndirectUserFromList(
            @NonNull final WritableKVState<IndirectKeyUsersKey, IndirectKeyUsersValue> indirectKeyUsers,
            @NonNull final Account.Builder keyAccountBuilder,
            @NonNull final AccountID keyAccountId,
            @NonNull final AccountID userId) {
        if (keyAccountId.account().kind() != ACCOUNT_NUM) {
            throw new IllegalArgumentException("Key account id is not numbered - " + keyAccountId);
        }
        if (userId.account().kind() != ACCOUNT_NUM) {
            throw new IllegalArgumentException("User account id is not numbered - " + userId);
        }
        final var target = IndirectKeyUsersKey.newBuilder()
                .keyAccountId(keyAccountId)
                .indirectUserId(userId)
                .build();
        final var value = indirectKeyUsers.get(target);
        if (value == null) {
            return;
        }
        final var prev = value.prevUserId();
        final var next = value.nextUserId();
        // Update previous' next pointer
        if (prev != null && !AccountID.DEFAULT.equals(prev)) {
            final var prevKey = IndirectKeyUsersKey.newBuilder()
                    .keyAccountId(keyAccountId)
                    .indirectUserId(prev)
                    .build();
            final var prevVal = indirectKeyUsers.get(prevKey);
            if (prevVal != null) {
                indirectKeyUsers.put(
                        prevKey, prevVal.copyBuilder().nextUserId(next).build());
            } else {
                log.warn("Previous indirect key user {} not found for key account {}", prev, keyAccountId);
            }
        } else {
            // We removed the head
            keyAccountBuilder.firstKeyUserId(next);
        }
        // Update next's prev pointer
        if (next != null && !AccountID.DEFAULT.equals(next)) {
            final var nextKey = IndirectKeyUsersKey.newBuilder()
                    .keyAccountId(keyAccountId)
                    .indirectUserId(next)
                    .build();
            final var nextVal = indirectKeyUsers.get(nextKey);
            if (nextVal != null) {
                indirectKeyUsers.put(
                        nextKey, nextVal.copyBuilder().prevUserId(prev).build());
            }
        }
        // Finally remove the (keyAccountId, userId) entry itself
        indirectKeyUsers.remove(target);
    }

    private static AccountID nextUserIdInList(
            @NonNull final WritableKVState<IndirectKeyUsersKey, IndirectKeyUsersValue> indirectKeyUsers,
            @NonNull final AccountID aId,
            @NonNull final AccountID current,
            @NonNull final AccountID first) {
        final var value = indirectKeyUsers.get(IndirectKeyUsersKey.newBuilder()
                .keyAccountId(aId)
                .indirectUserId(current)
                .build());
        final var next = (value == null) ? AccountID.DEFAULT : value.nextUserId();
        return (next == null || next.equals(AccountID.DEFAULT)) ? first : next;
    }

    private static void decrementIndirectUserCount(@NonNull final Account.Builder accountBuilder) {
        final int current = accountBuilder.build().numIndirectKeyUsers();
        accountBuilder.numIndirectKeyUsers(current > 0 ? current - 1 : 0);
    }

    private static void incrementIndirectUserCount(final Account.Builder accountBuilder) {
        final var current = accountBuilder.build().numIndirectKeyUsers();
        accountBuilder.numIndirectKeyUsers(current + 1);
    }

    private void requireNotDefault(@NonNull final Bytes alias) {
        if (alias.equals(Bytes.EMPTY)) {
            throw new IllegalArgumentException("Account ID cannot be default");
        }
    }
}
