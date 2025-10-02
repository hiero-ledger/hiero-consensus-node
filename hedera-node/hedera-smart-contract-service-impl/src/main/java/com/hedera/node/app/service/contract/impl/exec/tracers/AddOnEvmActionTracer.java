// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.tracers;

import static java.util.Objects.requireNonNull;

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
 * A {@link OperationTracer} that delegates just the relevant callbacks to a {@link EvmActionTracer}, and all
 * {@link OperationTracer} callbacks to a list of "add on" tracers.
 */
public class AddOnEvmActionTracer implements ActionSidecarContentTracer {
    private final EvmActionTracer evmActionTracer;
    private final List<OperationTracer> addOnTracers;

    /**
     * @param evmActionTracer the evm action tracer
     * @param addOnTracers all operation tracer callbacks
     */
    public AddOnEvmActionTracer(
            @NonNull final EvmActionTracer evmActionTracer, @NonNull final List<OperationTracer> addOnTracers) {
        this.evmActionTracer = requireNonNull(evmActionTracer);
        this.addOnTracers = requireNonNull(addOnTracers);
    }

    @Override
    public void traceOriginAction(@NonNull final MessageFrame frame) {
        evmActionTracer.traceOriginAction(frame);
    }

    @Override
    public void sanitizeTracedActions(@NonNull final MessageFrame frame) {
        evmActionTracer.sanitizeTracedActions(frame);
    }

    @Override
    public void tracePrecompileResult(@NonNull final MessageFrame frame, @NonNull final ContractActionType type) {
        evmActionTracer.tracePrecompileResult(frame, type);
    }

    @Override
    public @NonNull List<ContractAction> contractActions() {
        return evmActionTracer.contractActions();
    }

    // ---------------------------------------------------------------------------------------------
    // Two OperationTracer methods we delegate to both the evmActionTracer and our add-on tracers
    // ---------------------------------------------------------------------------------------------

    @Override
    public void tracePostExecution(
            @NonNull final MessageFrame frame, @NonNull final Operation.OperationResult operationResult) {
        evmActionTracer.tracePostExecution(frame, operationResult);
        addOnTracers.forEach(tracer -> tracer.tracePostExecution(frame, operationResult));
    }

    @Override
    public void traceAccountCreationResult(
            @NonNull final MessageFrame frame, @NonNull final Optional<ExceptionalHaltReason> haltReason) {
        evmActionTracer.traceAccountCreationResult(frame, haltReason);
        addOnTracers.forEach(tracer -> tracer.traceAccountCreationResult(frame, haltReason));
    }

    // ---------------------------------------------------------------------------------------------
    // The remaining OperationTracer methods aren't used for contract actions, so we only delegate
    // them to our add-on tracers, not the evmActionTracer
    // ---------------------------------------------------------------------------------------------

    @Override
    public void tracePreExecution(@NonNull final MessageFrame frame) {
        requireNonNull(frame);
        addOnTracers.forEach(tracer -> tracer.tracePreExecution(frame));
    }

    @Override
    public void tracePrecompileCall(
            @NonNull final MessageFrame frame, final long gasRequirement, @Nullable final Bytes output) {
        requireNonNull(frame);
        addOnTracers.forEach(tracer -> tracer.tracePrecompileCall(frame, gasRequirement, output));
    }

    @Override
    public void tracePrepareTransaction(@NonNull final WorldView worldView, @NonNull final Transaction transaction) {
        requireNonNull(worldView);
        requireNonNull(transaction);
        addOnTracers.forEach(tracer -> tracer.tracePrepareTransaction(worldView, transaction));
    }

    @Override
    public void traceStartTransaction(@NonNull final WorldView worldView, @NonNull final Transaction transaction) {
        requireNonNull(worldView);
        requireNonNull(transaction);
        addOnTracers.forEach(tracer -> tracer.traceStartTransaction(worldView, transaction));
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
        requireNonNull(worldView);
        requireNonNull(tx);
        requireNonNull(logs);
        addOnTracers.forEach(tracer ->
                tracer.traceEndTransaction(worldView, tx, status, output, logs, gasUsed, selfDestructs, timeNs));
    }

    @Override
    public void traceContextEnter(@NonNull final MessageFrame frame) {
        requireNonNull(frame);
        addOnTracers.forEach(tracer -> tracer.traceContextEnter(frame));
    }

    @Override
    public void traceContextReEnter(@NonNull final MessageFrame frame) {
        requireNonNull(frame);
        addOnTracers.forEach(tracer -> tracer.traceContextReEnter(frame));
    }

    @Override
    public void traceContextExit(@NonNull final MessageFrame frame) {
        requireNonNull(frame);
        addOnTracers.forEach(tracer -> tracer.traceContextExit(frame));
    }

    @Override
    public boolean isExtendedTracing() {
        return addOnTracers.stream().anyMatch(OperationTracer::isExtendedTracing);
    }
}
