// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.contract.impl.exec.operations.CustomCallOperation;
import com.hedera.node.app.spi.ids.EntityIdFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.code.CodeFactory;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Exposes the full Hedera state that may be read and changed <b>directly </b> from an EVM frame,
 * using data types appropriate to the Besu EVM API.
 *
 * <p>Of course, implementations must still reflect the state changes made by any calls to
 * the {@code 0x167} system contract from within the EVM frame. But those changes are, in a
 * sense, the result of "escaping" the EVM; so they are not part of this API.
 *
 * <p>Since almost all return values require translating from Hedera data types to Besu data
 * types, implementations might need to do internal caching to avoid excessive conversions.
 */
public interface EvmFrameState {
    /**
     * Returns the number of bytecodes in state; we use this to enforce the contract creation
     * limit.
     *
     * @return the number of bytecodes in state
     */
    long numBytecodesInState();

    /**
     * Tries to transfer the given amount from a sending contract to the recipient. The sender
     * has already authorized this action, in the sense that it is the address that has initiated
     * either a message call with value or a {@code selfdestruct}. The recipient, however, must
     * still be checked for authorization based on the Hedera concept of receiver signature
     * requirements.
     *
     * <p>Returns true if the receiver authorization and transfer succeeded, false otherwise.
     *
     * @param sendingContract the sender of the transfer, already authorized
     * @param recipient       the recipient of the transfer, not yet authorized
     * @param amount          the amount to transfer
     * @param delegateCall    whether this transfer is done via code executed by a delegate call
     * @return a optional with the reason to halt if the transfer failed, or empty if it succeeded
     */
    Optional<ExceptionalHaltReason> tryTransfer(
            @NonNull Address sendingContract, @NonNull Address recipient, long amount, boolean delegateCall);

    /**
     * Tries to initialize a "lazy-created" account at the given address. The standard creation pattern for a
     * Hedera account gives the account's key immediately. Lazy creation does not, instead initializing the
     * account with just the EVM address <i>derived from</i> an ECDSA public key.
     *
     * <p>Once we encounter a HAPI transaction with a full-prefix signature from this key, we can then finalize
     * the account by giving it the full key.
     *
     * <p>Lazy creation can fail for at least three reasons:
     * <ol>
     *   <li>There may be no more preceding child records available to externalize the creation.</li>
     *   <li>The Hedera accounts limit may be have been reached.</li>
     *   <li>There could already be an expired account at the given address.</li>
     * </ol>
     * Note the {@link CustomCallOperation} will have already confirmed that the Hedera EVM in use supports
     * lazy creation, and that it is enabled by properties.
     *
     * @param address the address of the account to try to lazy-create
     * @return an optional {@link ExceptionalHaltReason} with the reason lazy creation could not be done
     */
    Optional<ExceptionalHaltReason> tryLazyCreation(@NonNull Address address);

    /**
     * Returns whether the account with the given address is a "hollow account"; that is, an account
     * created by a value transfer to a 20-byte alias, without an explicit cryptographic key given.
     * @param address the address of the account which should be checked if it is a hollow account
     * @return true if the account is hollow
     */
    boolean isHollowAccount(@NonNull Address address);

    /**
     * Given an address that is a "hollow account", finalizes the account as a contract.
     *
     * @param address the address of the hollow account to finalize
     */
    void finalizeHollowAccount(@NonNull Address address);

    /**
     * Attempts to track the given deletion of an account with the designated beneficiary, returning an optional
     * {@link ExceptionalHaltReason} to indicate whether the deletion could be successfully tracked.
     *
     * @param deleted the address of the account being deleted
     * @param beneficiary the address of the beneficiary of the deletion
     * @param frame the frame in which to track the deletion
     * @return an optional {@link ExceptionalHaltReason} with the reason deletion could not be tracked
     */
    Optional<ExceptionalHaltReason> tryTrackingSelfDestructBeneficiary(
            @NonNull Address deleted, @NonNull Address beneficiary, @NonNull MessageFrame frame);

    /**
     * Tracks the given deletion of an account with the designated beneficiary.
     *
     * @param deleted the address of the account being deleted, a contract
     * @param beneficiary the address of the beneficiary of the deletion
     * @param frame
     *
     * `Beneficiary` must not be a token or a schedule.  Contract `deleted` must not be any token's
     * treasury.  Contract `deleted` must not own any tokens.  These conditions are _not_ checked
     * by this method.
     */
    void trackSelfDestructBeneficiary(
            @NonNull Address deleted, @NonNull Address beneficiary, @NonNull MessageFrame frame);

    /**
     * Returns the read-only account with the given address, or {@code null} if the account is missing,
     * deleted, or expired; or if this get() used the account's "long zero" address and not is priority
     * EVM address.
     *
     * @param address the account address
     * @return the read-only account; or {@code null} if the account is missing, deleted, or expired
     */
    @Nullable
    Account getAccount(Address address);

    /**
     * Returns the mutable account with the given address, or {@code null} if the account is missing,
     * deleted, or expired; or if this get() used the account's "long zero" address and not is priority
     * EVM address.
     *
     * @param address the account address
     * @return the mutable account; or {@code null} if the account is missing, deleted, or expired
     */
    @Nullable
    MutableAccount getMutableAccount(Address address);

    /**
     * Returns the storage value for the contract with the given contract id and key.
     *
     * @param contractID the contract id
     * @param key the key
     * @return the storage value
     */
    @NonNull
    UInt256 getStorageValue(ContractID contractID, @NonNull UInt256 key);

    /**
     * Sets the storage value for the contract with the given contract id and key.
     * @param contractID the contract id
     * @param key the key
     * @param value the value to set
     */
    void setStorageValue(ContractID contractID, @NonNull UInt256 key, @NonNull UInt256 value);

    /**
     * Returns the original storage value for the contract with the given contract id and key.
     *
     * @param contractID the contract id
     * @param key the key
     * @return the original storage value
     */
    @NonNull
    UInt256 getOriginalStorageValue(ContractID contractID, @NonNull UInt256 key);

    /**
     * Returns the code for the account with the given contract id, or empty code if no such code exists.
     *
     * @param contractID the contract id
     * @return the code for the account
     */
    @NonNull
    Bytes getCode(ContractID contractID);

    /**
     * Sets the code for the contract with the given contract id. Only used during contract creation.
     *
     * @param contractID the contract id
     * @param code the new code
     */
    void setCode(ContractID contractID, @NonNull Bytes code);

    /**
     * Returns the redirect bytecode for the token with the given address, which must be a long-zero address.
     *
     * <p>Since a {@link TokenEvmAccount} never needs its Hedera entity number, we may as well use
     * the long-zero address there, and here.
     *
     * @param address the token long-zero address
     * @return the redirect code for the token
     */
    @NonNull
    Bytes getTokenRedirectCode(@NonNull Address address);

    /**
     * @param contractID the contract to extract its code hash
     * @return the code hash of the contract
     */
    @NonNull
    Hash getCodeHash(ContractID contractID, @NonNull final CodeFactory codeFactory);

    /**
     * Returns the hash of the redirect bytecode for the token with the given address, which must be a
     * long-zero address.
     *
     * <p>Since a {@link TokenEvmAccount} never needs its Hedera entity number, we may as well use
     * the long-zero address there, and here.
     *
     * @param address the token long-zero address
     * @return the redirect code for the token
     */
    @NonNull
    Hash getTokenRedirectCodeHash(@NonNull Address address);

    /**
     * Returns the redirect bytecode for the account with the given address.  This should only be called for regular accounts
     * that are not contracts.
     *
     * @param address the account address
     * @return the redirect code for the account
     */
    @NonNull
    Bytes getAccountRedirectCode(@Nullable Address address);

    /**
     * Returns the hash of the redirect bytecode for the account with the given address.
     *
     * @param address the account address
     * @return the redirect code for the account
     */
    @NonNull
    Hash getAccountRedirectCodeHash(@Nullable Address address);

    /**
     * Returns the redirect bytecode for the schedule with the given address.  This should only be called for schedule
     * transaction entities
     *
     * @param address the schedule address
     * @return the redirect code for the schedule
     */
    @NonNull
    Bytes getScheduleRedirectCode(@Nullable Address address);

    /**
     * Returns the hash of the redirect bytecode for the schedule with the given address.
     *
     * @param address the schedule address
     * @return the redirect code for the schedule
     */
    @NonNull
    Hash getScheduleRedirectCodeHash(@Nullable Address address);

    /**
     * Returns the native account with the given account id.
     *
     * @param accountID the account id
     * @return the native account
     */
    com.hedera.hapi.node.state.token.Account getNativeAccount(AccountID accountID);

    /**
     * Returns the nonce for the account with the given id.
     *
     * @param accountID the account id
     * @return the nonce
     */
    long getNonce(AccountID accountID);

    /**
     * Returns the number of treasury titles for the account with the given id.
     *
     * @param accountID the account ID
     * @return the number of treasury titles
     */
    int getNumTreasuryTitles(AccountID accountID);

    /**
     * Returns the number of positive token balances for the account with the given id.
     *
     * @param accountID the account ID
     * @return the number of positive token balances
     */
    int getNumPositiveTokenBalances(AccountID accountID);

    /**
     * Returns whether the account with the given id is a contract.
     *
     * @param accountID the account id number
     * @return whether the account is a contract
     */
    boolean isContract(AccountID accountID);

    /**
     * Sets the nonce for the account with the given number.
     *
     * @param number the account number
     * @param nonce the new nonce
     */
    void setNonce(long number, long nonce);

    /**
     * Returns the balance of the account with the given number.
     *
     * @param accountID the account id
     * @return the balance
     */
    Wei getBalance(AccountID accountID);

    /**
     * Returns the "priority" EVM address of the account or token with the given number, or null if the
     * account has been deleted.
     *
     * <p>The priority address is its 20-byte alias if applicable; or else the "long-zero" address
     * with the account number as the last 8 bytes of the zero address.
     *
     * @param number the account or token number
     * @return the priority EVM address of the account, or null if the account has been deleted
     * @throws IllegalArgumentException if the account does not exist
     */
    @Nullable
    Address getAddress(long number);

    /**
     * Returns the "priority" EVM address of the account with the given id, or null if the
     * account has been deleted.
     *
     * <p>The priority address is its 20-byte alias if applicable; or else the "long-zero" address
     * with the account number as the last 8 bytes of the zero address.
     *
     * @param accountID the account id
     * @return the priority EVM address of the account, or null if the account has been deleted
     * @throws IllegalArgumentException if the account does not exist
     */
    @Nullable
    Address getAddress(AccountID accountID);

    /**
     * Returns the Hedera entity number for the account or contract at the given address. Throws
     * if there is no account with this address.
     *
     * @param address the address of the account or contract
     * @return the Hedera entity number
     * @throws IllegalArgumentException if there is no account with this address
     */
    long getIdNumber(@NonNull Address address);

    /**
     * Returns a {@link TxStorageUsage} summarizing the storage changes made by the transaction.
     *
     * @return the full list of account-scoped storage changes
     */
    @NonNull
    TxStorageUsage getTxStorageUsage(boolean includeChangedSlotKeys);

    /**
     * Returns the size of the underlying K/V state for contract storage.
     *
     * @return the size of the K/V state
     */
    long getKvStateSize();

    /**
     * Returns the rent factors for the contract with the given id.
     *
     * @param contractID the contract id
     * @return the rent factors
     */
    @NonNull
    RentFactors getRentFactorsFor(ContractID contractID);

    /**
     * Returns the entity id factory.
     * @return the entity id factory
     */
    @NonNull
    EntityIdFactory entityIdFactory();
}
