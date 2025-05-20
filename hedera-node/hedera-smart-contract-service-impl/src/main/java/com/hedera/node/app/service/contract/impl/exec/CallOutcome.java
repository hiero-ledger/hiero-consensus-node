// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.hapi.streams.ContractActions;
import com.hedera.hapi.streams.ContractStateChanges;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransactionResult;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.contract.impl.records.ContractCreateStreamBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Summarizes the outcome of an EVM message call.
 *
 * @param result the result of the call
 * @param status the resolved status of the call
 * @param recipientId if known, the Hedera id of the contract that was called
 * @param actions any contract actions that should be externalized in a sidecar
 * @param stateChanges any contract state changes that should be externalized in a sidecar
 * @param hederaOpsDuration the duration of the evm ops performed during the transaction
 */
public record CallOutcome(
        @NonNull ContractFunctionResult result,
        @NonNull ResponseCodeEnum status,
        @Nullable ContractID recipientId,
        @Nullable ContractActions actions,
        @Nullable ContractStateChanges stateChanges,
        long hederaOpsDuration) {

    /**
     * @return whether some state changes appeared from the execution of the contract
     */
    public boolean hasStateChanges() {
        return stateChanges != null && !stateChanges.contractStateChanges().isEmpty();
    }

    /**
     * @param result the contract function result
     * @param hevmResult the result after EVM transaction execution
     * @return the EVM transaction outcome
     */
    public static CallOutcome fromResultsWithMaybeSidecars(
            @NonNull final ContractFunctionResult result, @NonNull final HederaEvmTransactionResult hevmResult) {
        return new CallOutcome(
                result,
                hevmResult.finalStatus(),
                hevmResult.recipientId(),
                hevmResult.actions(),
                hevmResult.stateChanges(),
                hevmResult.opsDuration());
    }

    /**
     * @param result the contract function result
     * @param hevmResult the result after EVM transaction execution
     * @return the EVM transaction outcome
     */
    public static CallOutcome fromResultsWithoutSidecars(
            @NonNull ContractFunctionResult result, @NonNull HederaEvmTransactionResult hevmResult) {
        return new CallOutcome(
                result, hevmResult.finalStatus(), hevmResult.recipientId(), null, null, hevmResult.opsDuration());
    }

    /**
     * @param result the result of the call
     * @param status the resolved status of the call
     * @param recipientId if known, the Hedera id of the contract that was called
     * @param actions any contract actions that should be externalized in a sidecar
     * @param stateChanges any contract state changes that should be externalized in a sidecar
     */
    public CallOutcome {
        requireNonNull(result);
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
        streamBuilder.contractCallResult(result);
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
     * Adds the create details to the given record builder.
     *
     * @param recordBuilder the record builder
     */
    public void addCreateDetailsTo(@NonNull final ContractCreateStreamBuilder recordBuilder) {
        requireNonNull(recordBuilder);
        recordBuilder.contractID(recipientIdIfCreated());
        recordBuilder.contractCreateResult(result);
        recordBuilder.withCommonFieldsSetFrom(this);
    }

    /**
     * Returns the ID of the contract that was created, or null if no contract was created.
     *
     * @return the ID of the contract that was created, or null if no contract was created
     */
    public @Nullable ContractID recipientIdIfCreated() {
        return representsTopLevelCreation() ? result.contractIDOrThrow() : null;
    }

    private boolean representsTopLevelCreation() {
        return isSuccess() && requireNonNull(result).hasEvmAddress();
    }

    private boolean callWasAborted() {
        return result.gasUsed() == 0L;
    }
}
