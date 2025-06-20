// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Provides read-only methods for interacting with the underlying data storage mechanisms for
 * working with Accounts.
 */
public interface ReadableAccountStore {

    /**
     * Fetches an {@link Account} object from state with the given {@link AccountID}. If the account could not be
     * fetched because the given account doesn't exist, returns {@code null}. It doesn't look up in alias state
     * to find the account.
     *
     * @param accountID given account id
     * @return {@link Account} object if successfully fetched or {@code null} if the account doesn't exist
     */
    @Nullable
    Account getAccountById(@NonNull AccountID accountID);

    /**
     * Fetches an {@link Account} object from state with the given {@link AccountID}. If the account could not be
     * fetched because the given account doesn't exist, returns {@code null}. It looks up in alias state
     * to find the account.
     *
     * @param accountID given account id or alias
     * @return {@link Account} object if successfully fetched or {@code null} if the account doesn't exist
     */
    @Nullable
    Account getAliasedAccountById(@NonNull AccountID accountID);

    /**
     * Fetches an {@link Account} object from state with the given alias. If the account could not be
     * fetched because the given account doesn't exist, returns {@code null}.
     *
     * @param alias    alias
     * @param shardNum the shard number
     * @param realmNum the realm number
     * @return AccountID object if successfully fetched or {@code null} if the account doesn't exist
     */
    @Nullable
    AccountID getAccountIDByAlias(final long shardNum, final long realmNum, @NonNull Bytes alias);

    /**
     * Gets whether the given alias is known to this store. It is known if it is present in the alias-to-accountID
     * map.
     * @param shardNum the shard number
     * @param realmNum the realm number
     * @param alias The alias to check.
     * @return true if the given alias has a mapping to an AccountID in this store
     */
    boolean containsAlias(final long shardNum, final long realmNum, @NonNull Bytes alias);

    /**
     * Returns true if the given account ID exists in state.
     *
     * @param accountID the ID to check
     * @return true if the account exists in state
     */
    boolean contains(@NonNull AccountID accountID);

    /**
     * Returns true if the given account ID exists in state, or if the given account ID is an alias that exists in
     * state.
     *
     * @param accountID the ID to check
     * @return true if the account exists in state
     */
    default boolean isMissing(@NonNull final AccountID accountID) {
        return getAliasedAccountById(accountID) == null;
    }

    /**
     * Returns the number of accounts in state.
     *
     * @return the number of accounts in state
     */
    long getNumberOfAccounts();

    /**
     * Fetches an {@link Account} object from state with the given {@link ContractID}. If the contract account could not
     * be fetched because the given contract doesn't exist, returns {@code null}.
     *
     * @param contractID given contract id
     * @return {@link Account} object if successfully fetched or {@code null} if the contract account doesn't exist
     */
    @Nullable
    default Account getContractById(@NonNull final ContractID contractID) {
        // ContractID and AccountID are the same thing, really, and contracts are accounts. So we convert
        // from the contract ID to an account ID and reuse the existing method (no need for something else).
        // If we look up the account based on the ID successfully, but it isn't a smart contract account, then
        // we return null (we didn't find a contract with that ID).
        final var builder =
                AccountID.newBuilder().shardNum(contractID.shardNum()).realmNum(contractID.realmNum());

        if (contractID.hasEvmAddress()) {
            builder.alias(contractID.evmAddressOrThrow());
        } else {
            builder.accountNum(contractID.contractNumOrElse(0L));
        }

        final var account = getAliasedAccountById(builder.build());
        return account == null || !account.smartContract() ? null : account;
    }

    /**
     * Returns the number of entities in the account state.
     *
     * @return the size of the account state
     */
    long sizeOfAccountState();

    /**
     * Warms the system by preloading an account into memory
     *
     * <p>The default implementation is empty because preloading data into memory is only used for some implementations.
     *
     * @param accountID the account id
     */
    default void warm(@NonNull final AccountID accountID) {}
}
