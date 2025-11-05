// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.mintcontrol;

import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_IS_PAUSED;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_HOOKS_CONTRACT_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static java.util.Objects.requireNonNull;
import static org.hyperledger.besu.evm.frame.MessageFrame.State.CODE_SUSPENDED;

import com.hedera.hapi.node.base.HookEntityId;
import com.hedera.hapi.node.base.HookId;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.code.CodeFactory;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Call for FiatTokenV1 mint control management operations.
 * This call checks if the target token has a mint_control_hook_id configured,
 * and if so, executes the hook inline by spawning a child frame that calls
 * the hook bytecode at address 0x16d with the original caller preserved.
 */
public class MintControlManagementCall extends AbstractCall {
    private final Token token;
    private final Bytes selector;
    private final Bytes inputBytes;
    private final CodeFactory codeFactory;

    /**
     * Constructor for MintControlManagementCall.
     *
     * @param gasCalculator the gas calculator
     * @param enhancement the enhancement
     * @param token the target token (may be null if token doesn't exist)
     * @param selector the function selector
     * @param inputBytes the full input bytes
     * @param codeFactory the code factory
     */
    public MintControlManagementCall(
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @Nullable final Token token,
            @NonNull final Bytes selector,
            @NonNull final Bytes inputBytes,
            @NonNull final CodeFactory codeFactory) {
        super(gasCalculator, enhancement, false);
        this.token = token;
        this.selector = requireNonNull(selector);
        this.inputBytes = requireNonNull(inputBytes);
        this.codeFactory = codeFactory;
    }

    @Override
    public @NonNull PricedResult execute(@NonNull final MessageFrame frame) {
        requireNonNull(frame);

        // If token doesn't exist, return INVALID_TOKEN_ID
        if (token == null) {
            return reversionWith(INVALID_TOKEN_ID, gasCalculator.viewGasRequirement());
        }

        // Check if token has a mint_control_hook_id configured
        if (!token.hasMintControlHookId()) {
            // Token exists but doesn't have mint control hook configured
            return reversionWith(NOT_SUPPORTED, gasCalculator.viewGasRequirement());
        }

        // Enforce paused status - if token is paused, revert
        if (token.paused()) {
            return reversionWith(TOKEN_IS_PAUSED, gasCalculator.viewGasRequirement());
        }

        // Get the hook from the hook store
        final var hookEntityId =
                HookEntityId.newBuilder().tokenId(token.tokenId()).build();
        final var hookId = HookId.newBuilder()
                .entityId(hookEntityId)
                .hookId(token.mintControlHookIdOrThrow())
                .build();

        final var evmHookStore = nativeOperations().writableEvmHookStore();
        final var hook = evmHookStore.getEvmHook(hookId);
        if (hook == null) {
            return reversionWith(HOOK_NOT_FOUND, gasCalculator.viewGasRequirement());
        }

        // Execute the hook inline by spawning a child frame
        // The child frame will call the hook bytecode at address 0x16d
        // with the original caller preserved
        return executeHookInline(frame, hook);
    }

    /**
     * Executes the hook inline by spawning a child frame that calls the hook
     * bytecode at address 0x16d with the original caller preserved.
     *
     * @param parentFrame the parent frame
     * @param hook the hook to execute
     * @return the result of the hook execution
     */
    private PricedResult executeHookInline(
            @NonNull final MessageFrame parentFrame,
            @NonNull final com.hedera.hapi.node.state.hooks.EvmHookState hook) {
        requireNonNull(parentFrame);
        requireNonNull(hook);

        // Get the hook bytecode from the contract state store
        final var hookContractId = hook.hookContractIdOrThrow();
        final var worldUpdater = (ProxyWorldUpdater) parentFrame.getWorldUpdater();

        // Access the bytecode through the account at the hook contract address
        final var hookContractAccount = worldUpdater.getHederaAccount(hookContractId);
        if (hookContractAccount == null) {
            return reversionWith(HOOK_NOT_FOUND, gasCalculator.viewGasRequirement());
        }

        final var hookCode = hookContractAccount.getCode();
        if (hookCode == null || hookCode.isEmpty()) {
            return reversionWith(HOOK_NOT_FOUND, gasCalculator.viewGasRequirement());
        }

        // Calculate gas for the child frame
        // Use all remaining gas from the parent frame
        final var childGasStipend = parentFrame.getRemainingGas();

        // Spawn a child frame to execute the hook
        // The sender is preserved from the parent frame (the original caller)
        // The contract address is 0x16d (HTS_HOOKS_CONTRACT_ADDRESS)
        // The input data is the original management function calldata
        MessageFrame.builder()
                .parentMessageFrame(parentFrame)
                .type(MessageFrame.Type.MESSAGE_CALL)
                .initialGas(childGasStipend)
                .address(HTS_HOOKS_CONTRACT_ADDRESS)
                .contract(HTS_HOOKS_CONTRACT_ADDRESS)
                .inputData(inputBytes)
                .sender(parentFrame.getSenderAddress()) // Preserve original caller
                .value(Wei.ZERO) // No value transfer
                .apparentValue(Wei.ZERO)
                .code(codeFactory.createCode(hookCode))
                .completer(child -> completeHookExecution(parentFrame, child))
                .build();

        // Suspend the parent frame while the child executes
        parentFrame.setState(CODE_SUSPENDED);

        // Return a placeholder result - the actual result will be set by the completer
        // Gas cost is 0 here because gas is managed by the child frame
        return gasOnly(successResult(Bytes.EMPTY, 0), com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS, false);
    }

    /**
     * Completes the hook execution by copying the child frame's result to the parent frame.
     *
     * @param parentFrame the parent frame
     * @param childFrame the child frame that executed the hook
     */
    private void completeHookExecution(
            @NonNull final MessageFrame parentFrame, @NonNull final MessageFrame childFrame) {
        requireNonNull(parentFrame);
        requireNonNull(childFrame);

        // Copy the child frame's output data to the parent frame
        parentFrame.setOutputData(childFrame.getOutputData());

        // Copy the child frame's state to the parent frame
        if (childFrame.getState() == MessageFrame.State.COMPLETED_SUCCESS) {
            parentFrame.setState(MessageFrame.State.COMPLETED_SUCCESS);
        } else {
            // If the child frame reverted or failed, propagate that to the parent
            parentFrame.setState(childFrame.getState());
            parentFrame.setRevertReason(childFrame.getRevertReason().orElse(Bytes.EMPTY));
        }
    }

    /**
     * Returns the token being operated on.
     * @return the token, or null if it doesn't exist
     */
    @Nullable
    public Token token() {
        return token;
    }

    /**
     * Returns the function selector.
     * @return the selector
     */
    @NonNull
    public Bytes selector() {
        return selector;
    }

    /**
     * Returns the full input bytes.
     * @return the input bytes
     */
    @NonNull
    public Bytes inputBytes() {
        return inputBytes;
    }
}
