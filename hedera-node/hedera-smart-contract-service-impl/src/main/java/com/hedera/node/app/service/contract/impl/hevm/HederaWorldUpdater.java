// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.hevm;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.contract.ContractCreateTransactionBody;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.SystemContractOperations;
import com.hedera.node.app.service.contract.impl.state.HederaEvmAccount;
import com.hedera.node.app.service.contract.impl.state.PendingCreation;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.service.contract.impl.state.TxStorageUsage;
import com.hedera.node.app.spi.ids.EntityIdFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;

/**
 * A {@link WorldUpdater} extension with additional methods for Hedera-specific operations.
 */
public interface HederaWorldUpdater extends WorldUpdater {
    /**
     * Packages the (<b>very</b> provisionally named and organized) Hedera "enhancements" available
     * in an updater's context.
     *
     * @param operations the enhanced operations
     * @param nativeOperations the enhanced native operations
     * @param systemOperations the enhanced system operations
     */
    record Enhancement(
            @NonNull HederaOperations operations,
            @NonNull HederaNativeOperations nativeOperations,
            @NonNull SystemContractOperations systemOperations) {
        /**
         * @param operations the enhanced operations
         * @param nativeOperations the enhanced native operations
         * @param systemOperations the enhanced system operations
         */
        public Enhancement {
            requireNonNull(operations);
            requireNonNull(nativeOperations);
            requireNonNull(systemOperations);
        }
    }

    /**
     * Returns the {@link Enhancement} available in this updater's context.
     *
     * @return the enhanced operations
     */
    @NonNull
    Enhancement enhancement();

    /**
     * Returns the {@link HederaEvmAccount} for the given account id, or null if no
     * such account (or contract).
     *
     * @param accountId the id of the account to get
     * @return the account, or null if no such account
     */
    @Nullable
    HederaEvmAccount getHederaAccount(@NonNull AccountID accountId);

    /**
     * Returns the {@link HederaEvmAccount} for the given contract id, or null if no
     * such contract (or account).
     *
     * @param contractId the id of the contract to get
     * @return the account, or null if no such account
     */
    @Nullable
    HederaEvmAccount getHederaAccount(@NonNull ContractID contractId);

    /**
     * Returns the {@link HederaEvmAccount} for the given address, or null if no
     * such contract (or account).
     *
     * @param address the id of the contract to get
     * @return the account, or null if no such account
     */
    @Nullable
    default HederaEvmAccount getHederaAccount(@NonNull Address address) {
        requireNonNull(address);
        final var maybeAccount = get(address);
        if (maybeAccount instanceof HederaEvmAccount account) {
            return account;
        }
        return null;
    }

    /**
     * Returns the {@code 0.0.X} Hedera contract id for the given address, including when
     * the address is pending creation.
     *
     * @param address the address to get the id for
     * @return the id of the account at the given address
     * @throws IllegalArgumentException if the address has no corresponding contract id
     */
    ContractID getHederaContractId(@NonNull Address address);

    /**
     * Returns the all the bytes of entropy available in this world.
     *
     * @return the available entropy
     */
    @NonNull
    Bytes entropy();

    /**
     * Collects the given fee from the given account. The caller should have already
     * verified that the account exists and has sufficient balance to pay the fee, so
     * this method surfaces any problem by throwing an exception.
     *
     * @param payerId the id of the account to collect the fee from
     * @param amount the amount to collect
     * @param withNonceIncrement if the nonce should be incremented
     * @throws IllegalArgumentException if the collection fails for any reason
     */
    void collectGasFee(@NonNull AccountID payerId, long amount, boolean withNonceIncrement);

    /**
     * Refunds the given fee to the given account. The caller should have already
     * verified that the account exists, so this method surfaces any problem by
     * throwing an exception.
     *
     * @param payerId the id of the account to refund the fee to
     * @param amount the amount to refund
     */
    void refundGasFee(@NonNull AccountID payerId, long amount);

    /**
     * Tries to transfer the given amount from a sending contract to the recipient. The sender
     * has already authorized this action, in the sense that it is the address that has initiated
     * either a message call with value or a {@code selfdestruct}. The recipient, however, must
     * still be checked for authorization based on the Hedera concept of receiver signature
     * requirements.
     *
     * <p>Returns {@code Optional.empty()} immediately if {@code amount} is zero; or if the receiver
     * authorization and transfer succeeded. Returns an optional of the halt reason otherwise.
     *
     * @param sendingContract the sender of the transfer, already authorized
     * @param recipient       the recipient of the transfer, not yet authorized
     * @param amount          the amount to transfer
     * @param delegateCall    whether this transfer is done via code executed by a delegate call
     * @return an optional with the reason to halt if the transfer failed, empty otherwise
     */
    Optional<ExceptionalHaltReason> tryTransfer(
            @NonNull Address sendingContract, @NonNull Address recipient, long amount, boolean delegateCall);

    /**
     * Attempts to lazy-create the account at the given address and decrement the gas remaining in the frame
     * (since lazy creation has significant gas costs above the standard EVM fee schedule).
     *
     * @param recipient the address of the account to create
     * @param frame the frame in which the account is being created
     * @return an optional with the reason to halt if the creation failed, or empty if it succeeded
     */
    Optional<ExceptionalHaltReason> tryLazyCreation(@NonNull Address recipient, @NonNull MessageFrame frame);

    /**
     * Attempts to track the given deletion of an account with the designated beneficiary, returning an optional
     * {@link ExceptionalHaltReason} to indicate whether the deletion could be successfully tracked.
     *
     * @param deleted the address of the account being deleted
     * @param beneficiary the address of the beneficiary of the deletion
     * @param frame
     * @return an optional {@link ExceptionalHaltReason} with the reason deletion could not be tracked
     */
    Optional<ExceptionalHaltReason> tryTrackingSelfDestructBeneficiary(
            @NonNull Address deleted, @NonNull Address beneficiary, MessageFrame frame);

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
    void trackSelfDestructBeneficiary(@NonNull Address deleted, @NonNull Address beneficiary, MessageFrame frame);

    /**
     * Given the HAPI operation initiating a top-level {@code CONTRACT_CREATION} message, sets up the
     * {@link PendingCreation} a {@link ProxyWorldUpdater} can use to complete the creation of the new
     * account in {@link ProxyWorldUpdater#createAccount(Address, long, Wei)}; returns the "long-zero" address
     * to be assigned to the new account.
     *
     * @param body the HAPI operation initiating the creation
     * @return the "long-zero" address to be assigned to the new account
     */
    Address setupTopLevelCreate(@NonNull ContractCreateTransactionBody body);

    /**
     * Given the HAPI operation initiating a top-level {@code CONTRACT_CREATION} message, sets up the
     * {@link PendingCreation} a {@link ProxyWorldUpdater} can use to complete the creation of the new
     * account in {@link ProxyWorldUpdater#createAccount(Address, long, Wei)}.
     *
     * @param body the HAPI operation initiating the creation
     * @param alias the canonical address for the top-level creation
     */
    void setupAliasedTopLevelCreate(@NonNull ContractCreateTransactionBody body, @NonNull Address alias);

    /**
     * Given the HAPI operation initiating a top-level {@code MESSAGE_CALL} that will lazy-create a new
     * account if successful, sets up the {@link PendingCreation} a {@link ProxyWorldUpdater} can use
     * to complete the lazy creation {@link ProxyWorldUpdater#createAccount(Address, long, Wei)}.
     *
     * @param alias the canonical address for the top-level lazy creation
     */
    void setupTopLevelLazyCreate(@NonNull Address alias);

    /**
     * Given the origin address of a {@code CONTRACT_CREATION} message, sets up the {@link PendingCreation}
     * this {@link ProxyWorldUpdater} will use to complete the creation of the new account in
     * {@link ProxyWorldUpdater#createAccount(Address, long, Wei)}; returns the "long-zero" address to be
     * assigned to the new account.
     *
     * @param origin the address of the origin of a {@code CONTRACT_CREATION} message, zero if a top-level message
     * @return the "long-zero" address to be assigned to the new account
     */
    Address setupInternalCreate(@NonNull Address origin);

    /**
     * Given the origin address of a {@code CONTRACT_CREATION} message, and either the canonical {@code CREATE1}
     * address, or the EIP-1014 address computed by an in-progress {@code CREATE2} operation, sets up the
     * {@link PendingCreation} this {@link ProxyWorldUpdater} will use to complete the creation of the new account
     * in {@link ProxyWorldUpdater#createAccount(Address, long, Wei)}.
     *
     * <p>Does not return anything, as the {@code CREATE2} address is already known.
     *
     * @param origin the address of the origin of a {@code CONTRACT_CREATION} message, zero if a top-level message
     * @param alias  the canonical address computed by an in-progress {@code CREATE} or {@code CREATE2} operation
     */
    void setupInternalAliasedCreate(@NonNull Address origin, @NonNull Address alias);

    /**
     * Returns whether this address refers to a hollow account (i.e. a lazy-created account that
     * has not yet been completed as either an EOA with a cryptographic key, or a contract created
     * with CREATE2.)
     *
     * @param address the address to check
     * @return whether the address refers to a hollow account
     */
    boolean isHollowAccount(@NonNull Address address);

    /**
     * Finalizes the creation of a hollow account as a contract created via CREATE2. This step doesn't
     * exist in Besu because there contracts are just normal accounts with code; but in Hedera, there
     * are a few other properties that need to be set to "convert" an account into a contract.
     *
     * @param address the hollow account to be finalized as a contract
     * @param parent the address of the "parent" account finalizing the hollow account
     */
    void finalizeHollowAccount(@NonNull Address address, @NonNull Address parent);

    /**
     * Returns a summary of all storage usage in this updater's transaction.
     * @return a summary of all storage usage in this updater's transaction
     */
    @NonNull
    TxStorageUsage getTxStorageUsage();

    /**
     * Returns the {@link ExchangeRate} for the current consensus timestamp
     * Delegates to {@link SystemContractOperations#currentExchangeRate()} ()}
     * @return the current exchange rate
     */
    @NonNull
    ExchangeRate currentExchangeRate();

    /**
     * Sets the world updater to not check for the existence of the contractId
     * in the ledger when the getHederaContractId() method is called
     * This is to improve Ethereum equivalence.
     */
    void setContractNotRequired();

    /**
     * Returns the {@link EntityIdFactory}
     *
     * @return the {@link EntityIdFactory}
     */
    default EntityIdFactory entityIdFactory() {
        return enhancement().nativeOperations().entityIdFactory();
    }
}
