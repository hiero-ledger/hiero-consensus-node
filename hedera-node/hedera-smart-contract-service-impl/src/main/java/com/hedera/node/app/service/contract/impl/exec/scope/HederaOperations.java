// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.scope;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.contract.ContractCreateTransactionBody;
import com.hedera.node.app.service.contract.impl.records.ContractCreateStreamBuilder;
import com.hedera.node.app.service.contract.impl.state.ContractStateStore;
import com.hedera.node.app.service.contract.impl.state.DispatchingEvmFrameState;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.service.token.api.ContractChangeSummary;
import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.spi.throttle.ThrottleAdviser;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import org.hyperledger.besu.datatypes.Address;

/**
 * Provides the Hedera operations that only a {@link ProxyWorldUpdater} needs (but not a {@link DispatchingEvmFrameState}.
 */
public interface HederaOperations {
    /**
     * A contract id to indicate that a given ContractId has a mismatch with the config.
     */
    ContractID MISSING_CONTRACT_ID = ContractID.newBuilder().contractNum(0).build();

    /**
     * Creates a new {@link HederaOperations} that is a child of this {@link HederaOperations}.
     *
     * @return a nested {@link HederaOperations}
     */
    @NonNull
    HederaOperations begin();

    /**
     * Commits all changes made within this {@link HederaOperations} to the parent {@link HederaOperations}. For
     * everything except records, these changes will only affect state if every ancestor up to
     * and including the root {@link HederaOperations} is also committed. Records are a bit different,
     * as even if the root {@link HederaOperations} reverts, any records created within this
     * {@link HederaOperations} will still appear in state; but those with status {@code SUCCESS} will
     * have with their stateful effects cleared from the record and their status replaced with
     * {@code REVERTED_SUCCESS}.
     */
    void commit();

    /**
     * Reverts all changes and ends this session, with the possible exception of records, as
     * described above.
     */
    void revert();

    /**
     * Returns the {@link WritableStates} the {@code ContractService} can use to update
     * its own state within this {@link HederaOperations}.
     *
     * @return the contract state reflecting all changes made up to this {@link HederaOperations}
     */
    ContractStateStore getStore();

    /**
     * Returns what will be the next new entity number.
     *
     * @return the next entity number
     */
    long peekNextEntityNumber();

    /**
     * Reserves a new entity number for a contract being created.
     *
     * @return the reserved entity number
     */
    long useNextEntityNumber();

    /**
     * Returns the maximum number of contracts that we should allow in this operation scope.
     *
     * @return the maximum number of contracts
     */
    long contractCreationLimit();

    /**
     * Returns the maximum number of accounts that we should allow in this operation scope.
     *
     * @return the maximum number of accounts
     */
    long accountCreationLimit();

    /**
     * Returns the entropy available in this scope. See <a href="https://hips.hedera.com/hip/hip-351">HIP-351</a>
     * for details on how the Hedera node implements this.
     *
     * @return the available entropy
     */
    @NonNull
    Bytes entropy();

    /**
     * Returns the lazy creation cost within this scope.
     *
     * @param recipient the recipient contract address
     * @return the lazy creation cost in gas
     */
    long lazyCreationCostInGas(@NonNull final Address recipient);

    /**
     * Returns the gas price in tinybars within this scope.
     *
     * @return the gas price in tinybars
     */
    long gasPriceInTinybars();

    /**
     * Given an amount in tinycents, return the equivalent value in tinybars at the
     * active exchange rate.
     *
     * @param tinycents the fee in tinycents
     * @return the equivalent cost in tinybars
     */
    long valueInTinybars(long tinycents);

    /**
     * Collects the given fee from the given account, within a stack frame that on
     * revert should have all side effects discarded.
     * <p>
     * The caller should have already verified that the account exists and has
     * sufficient balance to pay the fee, so this method surfaces any problem by
     * throwing an exception.
     *
     * @param payerId the account to collect the fee from
     * @param amount the amount to collect
     * @throws IllegalArgumentException if the collection fails for any reason
     */
    void collectHtsFee(@NonNull AccountID payerId, long amount);

    /**
     * Collects the given fee from the given account, for initial gas charging, so
     * it can be replayed on revert.
     * <p>
     * The caller should have already verified that the account exists and has
     * sufficient balance to pay the fee, so this method surfaces any problem by
     * throwing an exception.
     *
     * @param payerId the account to collect the fee from
     * @param amount the amount to collect
     * @param withNonceIncrement whether the nonce should be incremented
     * @throws IllegalArgumentException if the collection fails for any reason
     */
    void collectGasFee(@NonNull AccountID payerId, long amount, boolean withNonceIncrement);

    /**
     * Refunds the given {@code amount} of fees from the given {@code fromEntityNumber}.
     *
     * @param payerId the address of the account to refund the fees to
     * @param amount the amount of fees to collect
     */
    void refundGasFee(@NonNull AccountID payerId, long amount);

    /**
     * Replays gas charging (and possible refunding) in the given context.
     */
    void replayGasChargingIn(FeeCharging.Context feeChargingContext);

    /**
     * Attempts to charge the given {@code amount} of rent to the given {@code contractNumber}, with
     * preference to its auto-renew account (if any); falling back to charging the contract itself
     * if the auto-renew account does not exist or does not have sufficient balance.
     *
     * @param contractID         the id of the contract to charge
     * @param amount                 the amount to charge
     * @param itemizeStoragePayments whether to itemize storage payments in the record
     */
    void chargeStorageRent(ContractID contractID, long amount, boolean itemizeStoragePayments);

    /**
     * Updates the storage metadata for the given contract.
     *
     * @param contractID the id of the contract
     * @param firstKey       the first key in the storage linked list, or {@link Bytes#EMPTY} if the list is empty
     * @param netChangeInSlotsUsed      the net change in the number of storage slots used by the contract
     */
    void updateStorageMetadata(ContractID contractID, @NonNull Bytes firstKey, int netChangeInSlotsUsed);

    /**
     * Creates a new contract with the given entity number and EVM address; and also "links" the alias
     * if it is set.
     *
     * <p>Any inheritable Hedera-native properties managed by the {@code TokenService} should be set on
     * the new contract based on the given model account.
     *
     * <p>The record of this creation should only be externalized if the top-level HAPI transaction succeeds.
     *
     * @param number       the number of the contract to create
     * @param parentNumber the number of the contract whose properties the new contract should inherit
     * @param evmAddress   if not null, the EVM address to use as an alias of the created contract
     */
    void createContract(long number, long parentNumber, @Nullable final Bytes evmAddress);

    /**
     * Creates a new contract with the given entity number and EVM address; and also "links" the alias
     * if it is set.
     *
     * <p>Any inheritable Hedera-native properties managed by the {@code TokenService} should be set
     * the new contract based on the given {@link ContractCreateTransactionBody}.
     *
     * <p>The record of this creation should only be externalized if the top-level HAPI transaction succeeds.
     *
     * @param number     the number of the contract to create
     * @param op         the top-level operation creating this contract
     * @param evmAddress if not null, the EVM address to use as an alias of the created contract
     */
    void createContract(long number, @NonNull ContractCreateTransactionBody op, @Nullable final Bytes evmAddress);

    /**
     * Deletes the contract whose alias is the given {@code evmAddress}, and also "unlinks" the alias.
     * Signing requirements are waived, and the record of this deletion should only be externalized if
     * the top-level HAPI transaction succeeds.
     *
     * <p>The record of this creation should only be externalized if the top-level HAPI transaction succeeds.
     *
     * @param evmAddress the EVM address of the contract to delete
     */
    void deleteAliasedContract(@NonNull Bytes evmAddress);

    /**
     * Convenience method to delete an unaliased contract with the given number.
     *
     * @param number the number of the contract to delete
     */
    void deleteUnaliasedContract(long number);

    /**
     * Returns a list of the account numbers that have been modified in this scope.
     *
     * @return the list of modified account numbers
     */
    List<Long> getModifiedAccountNumbers();

    /**
     * Returns a summary of the changes made to contract state.
     *
     * @return a summary of the changes made to contract state
     */
    ContractChangeSummary summarizeContractChanges();

    /**
     * Returns number of slots used by the contract with the given number, ignoring any uncommitted
     * modifications already dispatched. If the contract did not exist before the transaction, returns
     * zero.
     *
     * @param contractID the contract id
     * @return the number of storage slots used by the contract, ignoring any uncommitted modifications
     */
    long getOriginalSlotsUsed(ContractID contractID);

    /**
     * Creates a {@link ContractCreateStreamBuilder}, containing information about the hollow account.
     * @param contractId    ContractId of hollow account
     * @param evmAddress    Evm address of hollow account
     */
    void externalizeHollowAccountMerge(@NonNull ContractID contractId, @Nullable Bytes evmAddress);

    /**
     * Given a {@link ContractID}, returns it if the shard and realm match for this node; otherwise,
     * returns {@link #MISSING_CONTRACT_ID}.
     *
     * @param contractId the contract ID to validate
     * @return the validated contract ID
     */
    ContractID shardAndRealmValidated(@NonNull ContractID contractId);

    /**
     * Given a {@link ContractID} and the current Hedera config, returns the given id if its shard and realm
     * match the config; otherwise, returns {@link #MISSING_CONTRACT_ID}.
     *
     * @param contractId the contract ID to validate
     * @param hederaConfig the current Hedera config
     * @return the validated contract ID
     */
    default ContractID configValidated(@NonNull final ContractID contractId, @NonNull final HederaConfig hederaConfig) {
        requireNonNull(contractId);
        requireNonNull(hederaConfig);
        return contractId.shardNum() == hederaConfig.shard() && contractId.realmNum() == hederaConfig.realm()
                ? contractId
                : MISSING_CONTRACT_ID;
    }

    /**
     * Returns the ThrottleAdvisor or null if it is not available in this scope.
     */
    @Nullable
    ThrottleAdviser getThrottleAdviser();
}
