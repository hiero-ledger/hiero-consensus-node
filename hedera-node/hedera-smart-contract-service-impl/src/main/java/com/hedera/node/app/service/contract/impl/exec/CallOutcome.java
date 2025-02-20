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
 * @param tinybarGasPrice the tinybar-denominated gas price used for the call
 * @param actions any contract actions that should be externalized in a sidecar
 * @param stateChanges any contract state changes that should be externalized in a sidecar
 */
public record CallOutcome(
        @NonNull ContractFunctionResult result,
        @NonNull ResponseCodeEnum status,
        @Nullable ContractID recipientId,
        long tinybarGasPrice,
        @Nullable ContractActions actions,
        @Nullable ContractStateChanges stateChanges) {

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
                hevmResult.gasPrice(),
                hevmResult.actions(),
                hevmResult.stateChanges());
    }

    /**
     * @param result the contract function result
     * @param hevmResult the result after EVM transaction execution
     * @return the EVM transaction outcome
     */
    public static CallOutcome fromResultsWithoutSidecars(
            @NonNull ContractFunctionResult result, @NonNull HederaEvmTransactionResult hevmResult) {
        return new CallOutcome(
                result, hevmResult.finalStatus(), hevmResult.recipientId(), hevmResult.gasPrice(), null, null);
    }

    /**
     * @param result the result of the call
     * @param status the resolved status of the call
     * @param recipientId if known, the Hedera id of the contract that was called
     * @param tinybarGasPrice the tinybar-denominated gas price used for the call
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
     * Adds the call details to the given record builder.
     *
     * @param recordBuilder the record builder
     */
    public void addCallDetailsTo(@NonNull final ContractCallStreamBuilder recordBuilder) {
        requireNonNull(recordBuilder);
        if (!callWasAborted()) {
            recordBuilder.contractID(recipientId);
        }
        recordBuilder.contractCallResult(result);
        recordBuilder.withCommonFieldsSetFrom(this);
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
     * Returns the gas cost of the call in tinybar (always zero if the call was aborted before constructing
     * the initial {@link org.hyperledger.besu.evm.frame.MessageFrame}).
     *
     * @return the gas cost of the call in tinybar
     */
    public long tinybarGasCost() {
        return tinybarGasPrice * result.gasUsed();
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
