// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.standalone;

import com.hedera.hapi.streams.ContractAction;
import com.hedera.hapi.streams.ContractActionType;
import com.hedera.node.app.service.contract.impl.exec.ActionSidecarContentTracer;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Transaction;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.log.Log;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.tracing.OperationTracer;
import org.hyperledger.besu.evm.worldstate.WorldView;

/**
 * An adapter that wraps an {@link OperationTracer} and implements {@link ActionSidecarContentTracer}.
 * This allows using Besu's standard tracers (like {@code StandardJsonTracer}) with the Hedera
 * contract execution infrastructure that expects {@link ActionSidecarContentTracer} instances.
 *
 * <p>All {@link OperationTracer} methods are delegated to the wrapped tracer, while the
 * {@link ActionSidecarContentTracer}-specific methods are no-ops.
 */
public class OperationTracerAdapter implements ActionSidecarContentTracer {
    private final OperationTracer delegate;

    /**
     * Creates a new adapter wrapping the given {@link OperationTracer}.
     *
     * @param delegate the tracer to wrap
     */
    public OperationTracerAdapter(@NonNull final OperationTracer delegate) {
        this.delegate = delegate;
    }

    // ActionSidecarContentTracer-specific methods - no-op implementations

    @Override
    public void traceOriginAction(@NonNull final MessageFrame frame) {
        // No-op - the wrapped tracer doesn't support this
    }

    @Override
    public void sanitizeTracedActions(@NonNull final MessageFrame frame) {
        // No-op - the wrapped tracer doesn't support this
    }

    @Override
    public void tracePrecompileResult(@NonNull final MessageFrame frame, @NonNull final ContractActionType type) {
        // No-op - the wrapped tracer doesn't support this
    }

    @Override
    @NonNull
    public List<ContractAction> contractActions() {
        return List.of();
    }

    // OperationTracer methods - delegate to wrapped tracer

    @Override
    public void tracePreExecution(@NonNull final MessageFrame frame) {
        delegate.tracePreExecution(frame);
    }

    @Override
    public void tracePostExecution(
            @NonNull final MessageFrame frame, @NonNull final Operation.OperationResult operationResult) {
        delegate.tracePostExecution(frame, operationResult);
    }

    @Override
    public void tracePrecompileCall(
            @NonNull final MessageFrame frame, final long gasRequirement, @Nullable final Bytes output) {
        delegate.tracePrecompileCall(frame, gasRequirement, output);
    }

    @Override
    public void traceAccountCreationResult(
            @NonNull final MessageFrame frame, @NonNull final Optional<ExceptionalHaltReason> haltReason) {
        delegate.traceAccountCreationResult(frame, haltReason);
    }

    @Override
    public void tracePrepareTransaction(@NonNull final WorldView worldView, @NonNull final Transaction transaction) {
        delegate.tracePrepareTransaction(worldView, transaction);
    }

    @Override
    public void traceStartTransaction(@NonNull final WorldView worldView, @NonNull final Transaction transaction) {
        delegate.traceStartTransaction(worldView, transaction);
    }

    @Override
    public void traceEndTransaction(
            @NonNull final WorldView worldView,
            @NonNull final Transaction tx,
            final boolean status,
            @Nullable final Bytes output,
            @NonNull final List<Log> logs,
            final long gasUsed,
            final Set<Address> selfDestructs,
            final long timeNs) {
        delegate.traceEndTransaction(worldView, tx, status, output, logs, gasUsed, selfDestructs, timeNs);
    }

    @Override
    public void traceContextEnter(@NonNull final MessageFrame frame) {
        delegate.traceContextEnter(frame);
    }

    @Override
    public void traceContextReEnter(@NonNull final MessageFrame frame) {
        delegate.traceContextReEnter(frame);
    }

    @Override
    public void traceContextExit(@NonNull final MessageFrame frame) {
        delegate.traceContextExit(frame);
    }

    @Override
    public boolean isExtendedTracing() {
        return delegate.isExtendedTracing();
    }
}

