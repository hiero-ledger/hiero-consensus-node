// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec;

import com.hedera.hapi.streams.ContractAction;
import com.hedera.hapi.streams.ContractActionType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hyperledger.besu.evm.frame.MessageFrame;
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
}
