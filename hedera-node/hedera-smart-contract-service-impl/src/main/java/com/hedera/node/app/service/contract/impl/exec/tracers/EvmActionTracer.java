// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.tracers;

import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.hasActionSidecarsEnabled;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.hasActionValidationEnabled;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.hasValidatedActionSidecarsEnabled;
import static java.util.Objects.requireNonNull;
import static org.hyperledger.besu.evm.frame.MessageFrame.State.CODE_EXECUTING;
import static org.hyperledger.besu.evm.frame.MessageFrame.State.CODE_SUSPENDED;

import com.hedera.hapi.streams.ContractAction;
import com.hedera.hapi.streams.ContractActionType;
import com.hedera.node.app.service.contract.impl.exec.ActionSidecarContentTracer;
import com.hedera.node.app.service.contract.impl.exec.utils.ActionStack;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Optional;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.operation.Operation;

/**
 * Tracer implementation that chooses an appropriate {@link ActionStack} method to call based on the
 * {@link MessageFrame} state and system configuration.
 */
public class EvmActionTracer implements ActionSidecarContentTracer {
    private static final Logger log = LogManager.getLogger(EvmActionTracer.class);

    private final ActionStack actionStack;

    /**
     * @param actionStack the action stack of the contract
     */
    public EvmActionTracer(@NonNull final ActionStack actionStack) {
        this.actionStack = requireNonNull(actionStack);
    }

    @Override
    public void traceOriginAction(@NonNull final MessageFrame frame) {
        requireNonNull(frame);
        if (hasActionSidecarsEnabled(frame)) {
            actionStack.pushActionOfTopLevel(frame);
        }
    }

    @Override
    public void sanitizeTracedActions(@NonNull final MessageFrame frame) {
        requireNonNull(frame);
        if (hasValidatedActionSidecarsEnabled(frame)) {
            actionStack.sanitizeFinalActionsAndLogAnomalies(frame, log, Level.WARN);
        }
    }

    @Override
    public void tracePostExecution(
            @NonNull final MessageFrame frame, @NonNull final Operation.OperationResult operationResult) {
        requireNonNull(frame);
        requireNonNull(operationResult);
        if (!hasActionSidecarsEnabled(frame)) {
            return;
        }
        final var state = frame.getState();
        if (state == CODE_SUSPENDED) {
            actionStack.pushActionOfIntermediate(frame);
        } else if (state != CODE_EXECUTING) {
            actionStack.finalizeLastAction(frame, stackValidationChoice(frame));
        }
    }

    @Override
    public void tracePrecompileResult(@NonNull final MessageFrame frame, @NonNull final ContractActionType type) {
        requireNonNull(type);
        requireNonNull(frame);
        if (hasActionSidecarsEnabled(frame)) {
            actionStack.finalizeLastStackActionAsPrecompile(frame, type, stackValidationChoice(frame));
        }
    }

    @Override
    public @NonNull List<ContractAction> contractActions() {
        return actionStack.asContractActions();
    }

    @Override
    public void traceAccountCreationResult(
            @NonNull final MessageFrame frame, @NonNull final Optional<ExceptionalHaltReason> haltReason) {
        requireNonNull(frame);
        requireNonNull(haltReason);
        // It is important NOT to finalize the last action on the stack unless a halt reason
        // is present, as otherwise the same action could be finalized twice depending on the
        // value returned from this tracer's isExtendedTracing()---c.f. the call in Besu's
        // ContractCreationProcessor given both the deposit fee and passing validation rules.
        // It is equally important that we DO finalize the last action here when a halt
        // reason is present, since that means creation failed before executing the frame's
        // code, and tracePostExecution() will never be called; so this is our only chance
        // to keep the action stack in sync with the message frame stack.
        if (hasActionSidecarsEnabled(frame) && haltReason.isPresent()) {
            actionStack.finalizeLastAction(frame, stackValidationChoice(frame));
        }
    }

    private ActionStack.Validation stackValidationChoice(@NonNull final MessageFrame frame) {
        requireNonNull(frame);
        return hasActionValidationEnabled(frame) ? ActionStack.Validation.ON : ActionStack.Validation.OFF;
    }
}
