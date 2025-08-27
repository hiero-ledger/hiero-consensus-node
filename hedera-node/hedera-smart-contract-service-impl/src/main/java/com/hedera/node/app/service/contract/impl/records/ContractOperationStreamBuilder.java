// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.records;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asPbjSlotUsages;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asPbjStateChanges;
import static com.hedera.node.config.types.StreamMode.BLOCKS;
import static com.hedera.node.config.types.StreamMode.RECORDS;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.output.MapUpdateChange;
import com.hedera.hapi.block.stream.trace.ContractSlotUsage;
import com.hedera.hapi.block.stream.trace.EvmTransactionLog;
import com.hedera.hapi.block.stream.trace.ExecutedInitcode;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.contract.ContractNonceInfo;
import com.hedera.hapi.node.state.contract.SlotKey;
import com.hedera.hapi.streams.ContractAction;
import com.hedera.hapi.streams.ContractActions;
import com.hedera.hapi.streams.ContractBytecode;
import com.hedera.hapi.streams.ContractStateChanges;
import com.hedera.node.app.service.contract.impl.exec.CallOutcome;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.record.DeleteCapableTransactionStreamBuilder;
import com.hedera.node.config.data.BlockStreamConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A {@code StreamBuilder} specialization for tracking the side effects of any transaction related to contracts.
 * These include:
 * <ol>
 *     <li>{@link HederaFunctionality#CRYPTO_DELETE}</li>
 *     <li>{@link HederaFunctionality#CONTRACT_DELETE}</li>
 *     <li>{@link HederaFunctionality#CONTRACT_CREATE}</li>
 *     <li>{@link HederaFunctionality#CONTRACT_CALL}</li>
 *     <li>{@link HederaFunctionality#ETHEREUM_TRANSACTION}</li>
 * </ol>
 * transaction.
 */
public interface ContractOperationStreamBuilder extends DeleteCapableTransactionStreamBuilder {
    /**
     * Sets the predicate the builder will expose to downstream clients producing {@link MapUpdateChange}s
     * in the block stream, to check if a particular storage update changed just {@code prev}/{@code next}
     * pointers but was logically identical to the previous value.
     * @return this
     */
    ContractOperationStreamBuilder testForIdenticalKeys(@NonNull Predicate<Object> test);

    /**
     * Sets the transaction fee.
     *
     * @param transactionFee the new transaction fee
     * @return the updated {@link ContractOperationStreamBuilder}
     */
    ContractOperationStreamBuilder transactionFee(long transactionFee);

    /**
     * Tracks the ID of an account that should be explicitly considered as in a "reward situation"; that is, to collect
     * any pending native staking rewards it has accrued.
     *
     * @param accountId the account ID
     */
    void trackExplicitRewardSituation(@NonNull AccountID accountId);

    /**
     * Gets the set of contract IDs called during the transaction.
     *
     * @return the set of contract IDs called during the transaction
     */
    Set<AccountID> explicitRewardSituationIds();

    /**
     * Updates this record builder to include the standard contract fields from the given outcome.
     *
     * @param outcome the EVM transaction outcome
     * @param context the handle context
     * @return this updated builder
     */
    default ContractOperationStreamBuilder withCommonFieldsSetFrom(
            @NonNull final CallOutcome outcome, @NonNull final HandleContext context) {
        requireNonNull(outcome);
        requireNonNull(context);
        if (outcome.actions() != null) {
            // (FUTURE) Remove after switching to block stream
            addContractActions(new ContractActions(outcome.actions()), false);
            // No-op for the RecordStreamBuilder
            addActions(outcome.actions());
        }
        if (outcome.hasTxStorageUsage()) {
            final var txStorageUsage = outcome.txStorageUsageOrThrow();
            final var storageAccesses = txStorageUsage.accesses();
            // (FUTURE) Remove this check after switching to block stream
            final var streamMode = context.configuration()
                    .getConfigData(BlockStreamConfig.class)
                    .streamMode();
            if (streamMode != BLOCKS && !storageAccesses.isEmpty()) {
                addContractStateChanges(requireNonNull(asPbjStateChanges(storageAccesses)), false);
            }
            final boolean traceExplicitWrites = !txStorageUsage.hasChangedKeys();
            if (streamMode != RECORDS) {
                addContractSlotUsages(requireNonNull(asPbjSlotUsages(storageAccesses, traceExplicitWrites)));
            }
            if (!traceExplicitWrites) {
                final var changedKeys = txStorageUsage.changedKeysOrThrow();
                testForIdenticalKeys(o -> {
                    if (o instanceof SlotKey slotKey) {
                        return !changedKeys.contains(slotKey);
                    }
                    return false;
                });
            }
        }
        // No-ops for the RecordStreamBuilder
        if (outcome.hasLogs()) {
            addLogs(outcome.logsOrThrow());
        }
        if (outcome.hasChangedNonces()) {
            changedNonceInfo(outcome.changedNonceInfosOrThrow());
        }
        if (outcome.hasCreatedContractIds()) {
            createdContractIds(outcome.createdContractIdsOrThrow());
        }
        return this;
    }

    /**
     * Updates this record builder to include contract actions.
     *
     * @param contractActions the contract actions
     * @param isMigration     whether these actions are exported as part of a system-initiated migration of some kind
     * @return this builder
     */
    @NonNull
    @Deprecated
    ContractOperationStreamBuilder addContractActions(@NonNull ContractActions contractActions, boolean isMigration);

    /**
     * Updates this record builder to include contract actions.
     *
     * @param actions the contract actions
     * @return this builder
     */
    @NonNull
    ContractOperationStreamBuilder addActions(@NonNull List<ContractAction> actions);

    /**
     * Updates this record builder to include contract bytecode.
     *
     * @param contractBytecode the contract bytecode
     * @param isMigration      whether this bytecode is exported as part of a system-initiated migration of some kind
     * @return this builder
     */
    @NonNull
    @Deprecated
    ContractOperationStreamBuilder addContractBytecode(@NonNull ContractBytecode contractBytecode, boolean isMigration);

    /**
     * Updates this builder to include contract initcode.
     *
     * @param initcode the contract initcode
     * @return this builder
     */
    @NonNull
    ContractOperationStreamBuilder addInitcode(@NonNull ExecutedInitcode initcode);

    /**
     * Updates this record builder to include contract state changes.
     *
     * @param contractStateChanges the contract state changes
     * @param isMigration          whether these state changes are exported as part of a system-initiated migration of
     *                             some kind
     * @return this builder
     */
    @NonNull
    @Deprecated
    ContractOperationStreamBuilder addContractStateChanges(
            @NonNull ContractStateChanges contractStateChanges, boolean isMigration);

    /**
     * Updates this stream builder to include contract slot usages.
     *
     * @param slotUsages the contract slot usages
     * @return this builder
     */
    @NonNull
    ContractOperationStreamBuilder addContractSlotUsages(@NonNull List<ContractSlotUsage> slotUsages);

    /**
     * Tracks the EVM logs of a top-level contract transaction.
     * @param logs the list of {@link EvmTransactionLog}s to track
     * @return this builder
     */
    @NonNull
    ContractOperationStreamBuilder addLogs(@NonNull List<EvmTransactionLog> logs);

    /**
     * Tracks the contract ids that have changed nonce values as a result of this contract creation.
     * @return this builder
     */
    @NonNull
    ContractOperationStreamBuilder changedNonceInfo(@NonNull List<ContractNonceInfo> nonceInfos);

    /**
     * Tracks the contract ids that were created during a top-level EVM transaction.
     * @return this builder
     */
    @NonNull
    ContractOperationStreamBuilder createdContractIds(@NonNull List<ContractID> contractIds);
}
