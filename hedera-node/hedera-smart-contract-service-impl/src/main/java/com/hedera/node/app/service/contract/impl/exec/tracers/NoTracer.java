// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.tracers;

import com.hedera.hapi.streams.CallOperationType;
import com.hedera.hapi.streams.ContractActionType;
import com.hedera.node.app.service.contract.impl.exec.ActionSidecarContentTracer;
import java.util.List;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.operation.Operation;

public class NoTracer implements ActionSidecarContentTracer {
    public static final NoTracer NO_TRACER = new NoTracer();
    @Override public void traceOriginAction(MessageFrame frame) { }
    @Override public void sanitizeTracedActions(MessageFrame frame) { }
    @Override public void tracePrecompileResult(MessageFrame frame, ContractActionType type) { }
    @Override public void tracePostExecution(MessageFrame frame, Operation.OperationResult operationResult) { }
    @Override public List contractActions() { return null; }
    @Override public void tracePerOpcode(MessageFrame frame, long gas, ExceptionalHaltReason halt, Operation op) { }
    @Override public void traceSuspended(MessageFrame parent, MessageFrame child, CallOperationType opCall) { }
    @Override public void traceNotExecuting(MessageFrame child) { }
}
