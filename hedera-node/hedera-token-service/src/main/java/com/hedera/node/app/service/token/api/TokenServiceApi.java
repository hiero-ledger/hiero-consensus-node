// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.api;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.record.DeleteCapableTransactionStreamBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.info.NetworkInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.function.LongConsumer;
import java.util.function.ObjLongConsumer;

/**
 * Defines mutations that can't be expressed as a {@link com.hedera.hapi.node.transaction.TransactionBody} dispatch.
 *
 * <p>Only exported to the contract service at this time, as it is the only service that currently needs such a thing.
 * If, for example, we extract a {@code StakingService}, this API would likely need to expand.
 */
public interface TokenServiceApi {
    /**
     * Checks if the given transfer operation uses custom fees.
     *
     * @param op the transfer operation check
     * @return true if the given transaction body has custom fees, false otherwise
     */
    boolean checkForCustomFees(@NonNull CryptoTransferTransactionBody op);

    /**
     * Whether to free the alias when account is deleted or not.
     * Aliases are always freed when we use ContractDeleteTransactionBody.
     * Aliases are freed when we use CryptoDeleteTransactionBody based on the value of the
     * accountsConfig#releaseAliasAfterDeletion
     */
    enum FreeAliasOnDeletion {
        /**
         * Free the alias on deletion.
         */
        YES,
        /**
         * Do not free the alias on deletion.
         */
        NO
    }

    /**
     * Deletes the account with the given id and transfers any remaining hbar balance to the given obtainer id.
     *
     * @param deletedId the id of the account to delete
     * @param obtainerId the id of the account to transfer the remaining hbar balance to
     * @param expiryValidator the expiry validator to use
     * @param recordBuilder the record builder to record the transfer in
     * @param freeAliasOnDeletion whether to free the deleted account's alias (if any)
     * @throws HandleException if the account could not be deleted for some reason
     */
    void deleteAndTransfer(
            @NonNull AccountID deletedId,
            @NonNull AccountID obtainerId,
            @NonNull ExpiryValidator expiryValidator,
            @NonNull DeleteCapableTransactionStreamBuilder recordBuilder,
            @NonNull FreeAliasOnDeletion freeAliasOnDeletion);

    /**
     * Validates the creation of a given staking election relative to the given account store, network info,
     * and staking config.
     *
     * @param isStakingEnabled       if staking is enabled
     * @param hasDeclineRewardChange if the transaction body has decline reward field to be updated
     * @param stakedIdKind           staked id kind (account or node)
     * @param stakedAccountIdInOp    staked account id
     * @param stakedNodeIdInOp       staked node id
     * @param accountStore           readable account store
     * @param networkInfo            network info
     * @throws HandleException if the staking election is invalid
     */
    void assertValidStakingElectionForCreation(
            boolean isStakingEnabled,
            boolean hasDeclineRewardChange,
            @NonNull String stakedIdKind,
            @Nullable AccountID stakedAccountIdInOp,
            @Nullable Long stakedNodeIdInOp,
            @NonNull ReadableAccountStore accountStore,
            @NonNull NetworkInfo networkInfo);

    /**
     * Validates the update of a given staking election relative to the given account store, network info,
     * and staking config.
     *
     * @param isStakingEnabled       if staking is enabled
     * @param hasDeclineRewardChange if the transaction body has decline reward field to be updated
     * @param stakedIdKind           staked id kind (account or node)
     * @param stakedAccountIdInOp    staked account id
     * @param stakedNodeIdInOp       staked node id
     * @param accountStore           readable account store
     * @param networkInfo            network info
     * @throws HandleException if the staking election is invalid
     */
    void assertValidStakingElectionForUpdate(
            boolean isStakingEnabled,
            boolean hasDeclineRewardChange,
            @NonNull String stakedIdKind,
            @Nullable AccountID stakedAccountIdInOp,
            @Nullable Long stakedNodeIdInOp,
            @NonNull ReadableAccountStore accountStore,
            @NonNull NetworkInfo networkInfo);

    /**
     * Marks an account as a contract.
     *
     * @param accountId the id of the account to mark as a contract
     * @param autoRenewAccountId the id of the account to use for auto-renewing the contract
     */
    void markAsContract(@NonNull AccountID accountId, @Nullable AccountID autoRenewAccountId);

    /**
     * Finalizes a hollow account as a contract.
     *
     * @param hollowAccountId the id of the hollow account to finalize as a contract
     */
    void finalizeHollowAccountAsContract(@NonNull AccountID hollowAccountId);

    /**
     * Deletes the contract with the given id.
     *
     * @param contractId the id of the contract to delete
     */
    void deleteContract(@NonNull ContractID contractId);

    /**
     * Increments the nonce of the given contract.
     *
     * @param parentId the id of the contract whose nonce should be incremented
     */
    void incrementParentNonce(@NonNull ContractID parentId);

    /**
     * Increments the nonce of the given sender.
     *
     * @param senderId the id of the sender whose nonce should be incremented
     */
    void incrementSenderNonce(@NonNull AccountID senderId);

    /**
     * Sets the nonce of the given account.
     *
     * @param accountId the id of the account whose nonce should set
     * @param nonce the nonce to set
     */
    void setNonce(@NonNull AccountID accountId, long nonce);

    /**
     * Transfers the given amount from the given sender to the given recipient.
     *
     * @param from the id of the sender
     * @param to the id of the recipient
     * @param amount the amount to transfer
     */
    void transferFromTo(@NonNull AccountID from, @NonNull AccountID to, long amount);

    /**
     * Returns a summary of the changes made to contract state.
     *
     * @return a summary of the changes made to contract state
     */
    ContractChangeSummary summarizeContractChanges();

    /**
     * Updates the storage metadata for the given contract.
     *
     * @param contractID the id of the contract
     * @param firstKey       the first key in the storage linked list, {@link Bytes#EMPTY} if the storage is empty
     * @param netChangeInSlotsUsed      the net change in the number of storage slots used by the contract
     */
    void updateStorageMetadata(@NonNull ContractID contractID, @NonNull Bytes firstKey, int netChangeInSlotsUsed);

    /**
     * Charges the payer the given network fee, and records that fee in the given record builder.
     *
     * @param payer the id of the account that should be charged
     * @param amount the amount to charge
     * @param recordBuilder the record builder to record the fees in
     * @param cb if not null, a callback to receive the fee disbursements
     * @return true if the full amount was charged, false otherwise
     */
    boolean chargeNetworkFee(
            @NonNull AccountID payer,
            long amount,
            @NonNull FeeStreamBuilder recordBuilder,
            @Nullable ObjLongConsumer<AccountID> cb);

    /**
     * Charges the payer the given fees, and records those fees in the given record builder.
     *
     * @param payer the id of the account that should be charged
     * @param nodeAccount the id of the node that should receive the node fee, if present and payable
     * @param fees the fees to charge
     * @param recordBuilder the record builder to record the fees in
     * @param cb if not null, a map to record the balance adjustments in
     * @param onNodeFee a callback to receive the node fee disbursement
     */
    void chargeFees(
            @NonNull AccountID payer,
            AccountID nodeAccount,
            @NonNull Fees fees,
            @NonNull FeeStreamBuilder recordBuilder,
            @Nullable ObjLongConsumer<AccountID> cb,
            @NonNull LongConsumer onNodeFee);

    /**
     * Refunds the given fees to the given receiver, and records those fees in the given record builder.
     *
     * @param receiver      the id of the account that should be refunded
     * @param fees          the fees to refund
     * @param recordBuilder the record builder to record the fees in
     */
    void refundFees(@NonNull AccountID receiver, @NonNull Fees fees, @NonNull FeeStreamBuilder recordBuilder);

    /**
     * Returns the number of storage slots used by the given account before any changes were made via
     * this {@link TokenServiceApi}.
     *
     * @param id the id of the account
     * @return the number of storage slots used by the given account before any changes were made
     */
    long originalKvUsageFor(@NonNull ContractID id);

    /**
     * Updates the passed contract.
     * @param contract the contract that is updated
     */
    void updateContract(Account contract);
}
