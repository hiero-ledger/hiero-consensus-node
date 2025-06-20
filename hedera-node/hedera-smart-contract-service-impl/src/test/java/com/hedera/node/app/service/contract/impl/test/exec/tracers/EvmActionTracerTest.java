// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.tracers;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.streams.ContractActionType;
import com.hedera.node.app.service.contract.impl.exec.tracers.EvmActionTracer;
import com.hedera.node.app.service.contract.impl.exec.utils.ActionStack;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.operation.Operation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EvmActionTracerTest {
    @Mock
    private ActionStack actionStack;

    @Mock
    private MessageFrame frame;

    @Mock
    private Deque<MessageFrame> stack;

    private EvmActionTracer subject;

    @BeforeEach
    void setUp() {
        given(frame.getMessageFrameStack()).willReturn(stack);
        given(stack.isEmpty()).willReturn(true);
        subject = new EvmActionTracer(actionStack);
    }

    @Test
    void customInitIsNoopWithoutActionSidecars() {
        givenNoActionSidecars();
        given(actionStack.asContractActions()).willReturn(List.of());

        subject.traceOriginAction(frame);

        verifyNoInteractions(actionStack);
        assertTrue(subject.contractActions().isEmpty());
    }

    @Test
    void customInitTracksTopLevel() {
        givenSidecarsOnly();

        subject.traceOriginAction(frame);

        verify(actionStack).pushActionOfTopLevel(frame);
    }

    @Test
    void customFinalizeNoopIfNoActionSidecars() {
        givenNoActionSidecars();

        subject.sanitizeTracedActions(frame);

        verifyNoInteractions(actionStack);
    }

    @Test
    void customFinalizeNoopIfNotValidatingActions() {
        givenSidecarsOnly();

        subject.sanitizeTracedActions(frame);

        verifyNoInteractions(actionStack);
    }

    @Test
    void customFinalizeSanitizesActionsIfValidating() {
        givenActionSidecarsAndValidation();

        subject.sanitizeTracedActions(frame);

        verify(actionStack).sanitizeFinalActionsAndLogAnomalies(eq(frame), any(Logger.class), eq(Level.WARN));
    }

    @Test
    void postExecNoopWithoutActionSidecars() {
        givenNoActionSidecars();

        subject.tracePostExecution(frame, new Operation.OperationResult(123, null));

        verifyNoInteractions(actionStack);
    }

    @Test
    void postExecNoopIfCodeExecutingState() {
        givenSidecarsOnly();
        given(frame.getState()).willReturn(MessageFrame.State.CODE_EXECUTING);

        subject.tracePostExecution(frame, new Operation.OperationResult(123, null));

        verifyNoInteractions(actionStack);
    }

    @Test
    void postExecTracksIntermediateIfSuspended() {
        givenSidecarsOnly();
        given(frame.getState()).willReturn(MessageFrame.State.CODE_SUSPENDED);

        subject.tracePostExecution(frame, new Operation.OperationResult(123, null));

        verify(actionStack).pushActionOfIntermediate(frame);
    }

    @Test
    void postExecFinalizesIfNotSuspended() {
        givenActionSidecarsAndValidation();
        given(frame.getState()).willReturn(MessageFrame.State.COMPLETED_SUCCESS);

        subject.tracePostExecution(frame, new Operation.OperationResult(123, null));

        verify(actionStack).finalizeLastAction(frame, ActionStack.Validation.ON);
    }

    @Test
    void precompileTraceIsNoopIfNoSidecars() {
        givenNoActionSidecars();

        subject.tracePrecompileResult(frame, ContractActionType.SYSTEM);

        verifyNoInteractions(actionStack);
    }

    @Test
    void systemPrecompileTraceIsStillTrackedEvenIfHalted() {
        givenSidecarsOnly();

        subject.tracePrecompileResult(frame, ContractActionType.SYSTEM);

        verify(actionStack)
                .finalizeLastStackActionAsPrecompile(frame, ContractActionType.SYSTEM, ActionStack.Validation.OFF);
    }

    @Test
    void accountCreationTraceIsNoopIfNoSidecars() {
        givenNoActionSidecars();

        subject.traceAccountCreationResult(frame, Optional.empty());

        verifyNoInteractions(actionStack);
    }

    @Test
    void accountCreationTraceDoesNotFinalizesEvenWithSidecarsUnlessHaltReasonProvided() {
        givenSidecarsOnly();

        subject.traceAccountCreationResult(frame, Optional.empty());

        verifyNoInteractions(actionStack);
    }

    @Test
    void accountCreationTraceFinalizesWithSidecarsAndHaltReason() {
        givenActionSidecarsAndValidation();

        subject.traceAccountCreationResult(frame, Optional.of(ExceptionalHaltReason.INSUFFICIENT_GAS));

        verify(actionStack).finalizeLastAction(frame, ActionStack.Validation.ON);
    }

    private void givenNoActionSidecars() {
        givenConfig(false, false);
    }

    private void givenActionSidecarsAndValidation() {
        givenConfig(true, true);
    }

    private void givenSidecarsOnly() {
        givenConfig(true, false);
    }

    private void givenConfig(final boolean actionSidecars, final boolean validation) {
        if (actionSidecars) {
            given(frame.hasContextVariable(FrameUtils.ACTION_SIDECARS_VARIABLE)).willReturn(true);
        }
        if (validation) {
            given(frame.hasContextVariable(FrameUtils.ACTION_SIDECARS_VALIDATION_VARIABLE))
                    .willReturn(true);
        }
    }
}
