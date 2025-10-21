// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl;

import static com.hedera.hapi.node.base.AccountID.AccountOneOfType.ACCOUNT_NUM;
import static com.hedera.node.app.service.token.AliasUtils.asKeyFromAlias;
import static com.hedera.node.app.service.token.AliasUtils.extractEvmAddress;
import static com.hedera.node.app.service.token.AliasUtils.isAlias;
import static com.hedera.node.app.service.token.AliasUtils.isOfEvmAddressSize;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.contract.ContractNonceInfo;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.entityid.WritableEntityCounters;
import com.hedera.node.app.service.token.api.ContractChangeSummary;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Provides write methods for modifying underlying data storage mechanisms for working with
 * accounts.
 *
 * <p>This class is not exported from the module. It is an internal implementation detail. This
 * class is not complete, it will be extended with other methods like remove, update etc.,
 */
public class WritableAccountStore extends ReadableAccountStoreImpl {
    private final WritableEntityCounters entityCounters;

    /**
     * Create a new {@link WritableAccountStore} instance.
     *
     * @param states The state to use.
     */
    public WritableAccountStore(
            @NonNull final WritableStates states, @NonNull final WritableEntityCounters entityCounters) {
        super(states, entityCounters);
        this.entityCounters = entityCounters;
    }

    @Override
    protected WritableKVState<AccountID, Account> accountState() {
        return super.accountState();
    }

    @Override
    protected WritableKVState<ProtoBytes, AccountID> aliases() {
        return super.aliases();
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

    private void requireNotDefault(@NonNull final Bytes alias) {
        if (alias.equals(Bytes.EMPTY)) {
            throw new IllegalArgumentException("Account ID cannot be default");
        }
    }
}
