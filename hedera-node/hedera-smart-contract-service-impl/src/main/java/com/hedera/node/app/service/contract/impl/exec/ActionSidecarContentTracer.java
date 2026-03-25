// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec;

import com.hedera.hapi.streams.CallOperationType;
import com.hedera.hapi.streams.ContractAction;
import com.hedera.hapi.streams.ContractActionType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.tracing.OperationTracer;

/**
 * The Hedera-specific extensions to the {@link OperationTracer} interface we use to construct
 * and manage the {@link com.hedera.hapi.streams.ContractAction}'s in a sidecar of type
 * {@link com.hedera.hapi.streams.SidecarType#CONTRACT_ACTION}.
 */
public interface ActionSidecarContentTracer extends OperationTracer {
    /**
     * A hook we use to insert an action at the beginning of a transaction,
     * corresponding to the top-level HAPI operation.
     *
     * @param frame the initial frame of the just-beginning EVM transaction
     */
    void traceOriginAction(@NonNull MessageFrame frame);

    /**
     * A hook we use to "sanitize" any contract actions that have been
     * tracked during the transaction. Prevents invalid actions from
     * being exported to mirror nodes
     *
     * @param frame the initial frame of the just-finished EVM transaction
     */
    void sanitizeTracedActions(@NonNull MessageFrame frame);

    /**
     * A hook we use to manage the action sidecar of a precompile call result.
     *
     * @param frame the frame that called the precompile
     * @param type the type of precompile called; expected values are {@code PRECOMPILE} and {@code SYSTEM}
     */
    void tracePrecompileResult(@NonNull MessageFrame frame, @NonNull ContractActionType type);

    /**
     * The final list of actions traced by this tracer.
     *
     * @return the actions traced by this tracer
     */
    List<ContractAction> contractActions();

    /**
     * The BESU API design here requires an allocated OperationResult
     * per-opcode-executed which is a hard performance fail.  Replace it with an
     * "open" API where the parts (gas, halt reason) are passed in instead of
     * making a wrapper object.  Keeping the old API for running BESU EVM tests.
     * Hedera and Bonneville EVMs should not call this.
     */
    void tracePostExecution(MessageFrame frame, Operation.OperationResult operationResult);

    /**
     * Called by Bonneville in hot code.
     * Same as tracePostExecution when frame is executing
     */
    void tracePerOpcode(MessageFrame frame, long gas, ExceptionalHaltReason halt, Operation op);

    /**
     * Called by Bonneville in hot code.
     * Same as tracePostExecution when the parent is suspended, except parent
     * frame, child frame and opCall already broken out.
     */
    void traceSuspended(MessageFrame parent, MessageFrame child, CallOperationType opCall);

    /**
     * Called by Bonneville in hot code.
     * Same as tracePostExecution when the child is not-executing, except the child
     * frame is already broken out.
     */
    void traceNotExecuting(MessageFrame child);
}
