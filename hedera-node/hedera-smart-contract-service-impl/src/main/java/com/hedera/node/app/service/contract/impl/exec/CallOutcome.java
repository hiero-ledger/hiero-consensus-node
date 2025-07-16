// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.trace.ContractSlotUsage;
import com.hedera.hapi.block.stream.trace.EvmTransactionLog;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.hapi.node.contract.ContractNonceInfo;
import com.hedera.hapi.node.contract.EvmTransactionResult;
import com.hedera.hapi.streams.ContractAction;
import com.hedera.hapi.streams.ContractStateChanges;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransactionResult;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.contract.impl.records.ContractCreateStreamBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

/**
 * Summarizes the outcome of an EVM message call.
 *
 * @param result the result of the call
 * @param status the resolved status of the call
 * @param recipientId if known, the Hedera id of the contract that was called
 * @param actions any contract actions that should be externalized in a sidecar
 * @param stateChanges any contract state changes that should be externalized in a sidecar
 * @param slotUsages any contract slot usages that should be externalized in trace data
 * @param logs if not null, any EVM transaction logs that should be externalized in trace data
 * @param changedNonceInfos if not null, any contract IDs that had their nonce changed during the call
 * @param createdContractIds
 * @param txResult the concise EVM transaction result
 * @param newSenderNonce if not null, the new sender nonce after the call
 * @param createdEvmAddress
 */
public record CallOutcome(
        @NonNull @Deprecated ContractFunctionResult result,
        @NonNull ResponseCodeEnum status,
        @Nullable ContractID recipientId,
        @Nullable List<ContractAction> actions,
        @Nullable @Deprecated ContractStateChanges stateChanges,
        @Nullable List<ContractSlotUsage> slotUsages,
        @Nullable List<EvmTransactionLog> logs,
        @Nullable List<ContractNonceInfo> changedNonceInfos,
        @Nullable List<ContractID> createdContractIds,
        @NonNull EvmTransactionResult txResult,
        @Nullable Long newSenderNonce,
        @Nullable Bytes createdEvmAddress) {

    /**
     * Returns whether there is a new sender nonce after the call.
     */
    public boolean hasNewSenderNonce() {
        return newSenderNonce != null;
    }

    /**
     * Returns the new sender nonce after the call.
     */
    public long newSenderNonceOrThrow() {
        return requireNonNull(newSenderNonce);
    }

    /**
     * @return whether some state changes appeared from the execution of the contract
     */
    @Deprecated
    public boolean hasStateChanges() {
        return stateChanges != null && !stateChanges.contractStateChanges().isEmpty();
    }

    /**
     * @return whether some slot usages appeared from the execution of the contract
     */
    public boolean hasSlotUsages() {
        return slotUsages != null && !slotUsages.isEmpty();
    }

    /**
     * @return whether some logs appeared from the execution of the contract.
     */
    public boolean hasLogs() {
        return logs != null && !logs.isEmpty();
    }

    /**
     * @return whether some nonces were changed during the execution of the contract.
     */
    public boolean hasChangedNonces() {
        return changedNonceInfos != null && !changedNonceInfos.isEmpty();
    }

    /**
     * @return whether some contract IDs were created during the execution of the contract.
     */
    public boolean hasCreatedContractIds() {
        return createdContractIds != null && !createdContractIds.isEmpty();
    }

    /**
     * Return the slot usages.
     */
    public @NonNull List<ContractSlotUsage> slotUsagesOrThrow() {
        return requireNonNull(slotUsages);
    }

    /**
     * Return the logs from the EVM transaction.
     */
    public @NonNull List<EvmTransactionLog> logsOrThrow() {
        return requireNonNull(logs);
    }

    /**
     * Return the contract IDs that had their nonce changed during the call.
     */
    public @NonNull List<ContractNonceInfo> changedNonceInfosOrThrow() {
        return requireNonNull(changedNonceInfos);
    }

    /**
     * Return the contract IDs that were created during the call.
     */
    public @NonNull List<ContractID> createdContractIdsOrThrow() {
        return requireNonNull(createdContractIds);
    }

    /**
     * @param result the contract function result
     * @param txResult the concise EVM transaction result
     * @param changedNonceInfos if not null, the contract IDs that had their nonce changed during the call
     * @param createdContractIds if not null, the contract IDs that were created during the call
     * @param evmAddress if not null, the EVM address of the contract that created
     * @param hevmResult the result after EVM transaction execution
     * @return the EVM transaction outcome
     */
    public static CallOutcome fromResultsWithMaybeSidecars(
            @Deprecated @NonNull final ContractFunctionResult result,
            @NonNull final EvmTransactionResult txResult,
            @Nullable final List<ContractNonceInfo> changedNonceInfos,
            @Nullable final List<ContractID> createdContractIds,
            @Nullable final Bytes evmAddress,
            @NonNull final HederaEvmTransactionResult hevmResult) {
        return new CallOutcome(
                result,
                hevmResult.finalStatus(),
                hevmResult.recipientId(),
                hevmResult.actions(),
                hevmResult.stateChanges(),
                hevmResult.slotUsages(),
                hevmResult.evmLogs(),
                changedNonceInfos,
                createdContractIds,
                txResult,
                hevmResult.signerNonce(),
                evmAddress);
    }

    /**
     * @param result the contract function result
     * @param txResult the concise EVM transaction result
     * @param updatedNonceInfos if not null, the contract IDs that had their nonce changed during the call
     * @param createdContractIds if not null, the contract IDs that were created during the call
     * @param evmAddress if not null, the EVM address of the contract that created
     * @param hevmResult the result after EVM transaction execution
     * @return the EVM transaction outcome
     */
    public static CallOutcome fromResultsWithoutSidecars(
            @NonNull final ContractFunctionResult result,
            @NonNull final EvmTransactionResult txResult,
            @Nullable final List<ContractNonceInfo> updatedNonceInfos,
            @Nullable final List<ContractID> createdContractIds,
            @Nullable Bytes evmAddress,
            @NonNull final HederaEvmTransactionResult hevmResult) {
        return new CallOutcome(
                result,
                hevmResult.finalStatus(),
                hevmResult.recipientId(),
                null,
                null,
                null,
                hevmResult.evmLogs(),
                updatedNonceInfos,
                createdContractIds,
                txResult,
                hevmResult.signerNonce(),
                evmAddress);
    }

    /**
     * @param result the result of the call
     * @param status the resolved status of the call
     * @param recipientId if known, the Hedera id of the contract that was called
     * @param actions any contract actions that should be externalized in a sidecar
     * @param stateChanges any contract state changes that should be externalized in a sidecar
     * @param slotUsages any contract slot usages that should be externalized in trace data
     * @param logs any EVM transaction logs that should be externalized in trace data
     * @param changedNonceInfos the contract IDs that had their nonce changed during the call
     * @param createdContractIds if not null, the contract IDs that were created during the call
     * @param txResult the concise EVM transaction result
     * @param newSenderNonce if applicable, the new sender nonce after the call
     * @param createdEvmAddress if applicable, the EVM address of the contract that was created
     */
    public CallOutcome {
        requireNonNull(result);
        requireNonNull(txResult);
        requireNonNull(status);
    }

    /**
     * Returns true if the call was successful.
     *
     * @return true if the call was successful
     */
    public boolean isSuccess() {
        return status == SUCCESS;
    }

    /**
     * Adds the call details to the given stream builder.
     *
     * @param streamBuilder the stream builder
     */
    public void addCallDetailsTo(@NonNull final ContractCallStreamBuilder streamBuilder) {
        requireNonNull(streamBuilder);
        addCalledContractIfNotAborted(streamBuilder);
        // (FUTURE) Remove after switching to block stream
        streamBuilder.contractCallResult(result);
        // No-op for the RecordStreamBuilder
        streamBuilder.evmCallTransactionResult(txResult);
        streamBuilder.withCommonFieldsSetFrom(this);
    }

    /**
     * Adds the called contract ID to the given stream builder if the call was not aborted.
     * @param streamBuilder the stream builder
     */
    public void addCalledContractIfNotAborted(@NonNull final ContractCallStreamBuilder streamBuilder) {
        requireNonNull(streamBuilder);
        if (!callWasAborted()) {
            streamBuilder.contractID(recipientId);
        }
    }

    /**
     * Adds the creation details to the given stream builder.
     * @param streamBuilder the stream builder
     */
    public void addCreateDetailsTo(@NonNull final ContractCreateStreamBuilder streamBuilder) {
        requireNonNull(streamBuilder);
        // (FUTURE) Remove after switching to block stream
        streamBuilder.contractCreateResult(result);
        streamBuilder
                .createdContractID(recipientIdIfCreated())
                .createdEvmAddress(createdEvmAddress)
                .evmCreateTransactionResult(txResult)
                .withCommonFieldsSetFrom(this);
    }

    /**
     * Returns the ID of the contract that was created, or null if no contract was created.
     *
     * @return the ID of the contract that was created, or null if no contract was created
     */
    public @Nullable ContractID recipientIdIfCreated() {
        return representsTopLevelCreation() ? txResult.contractIdOrThrow() : null;
    }

    private boolean representsTopLevelCreation() {
        return isSuccess() && createdEvmAddress != null;
    }

    private boolean callWasAborted() {
        return txResult.gasUsed() == 0L;
    }
}
