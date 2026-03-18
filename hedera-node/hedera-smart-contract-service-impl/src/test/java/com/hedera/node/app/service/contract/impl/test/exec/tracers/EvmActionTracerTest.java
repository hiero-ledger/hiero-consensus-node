// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.tracers;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
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
        subject = new EvmActionTracer(actionStack);
    }

    @Test
    void customInitIsNoopWithoutActionSidecars() {
        given(frame.getMessageFrameStack()).willReturn(stack);
        given(stack.isEmpty()).willReturn(true);
        givenNoActionSidecars();
        given(actionStack.asContractActions()).willReturn(List.of());

        subject.traceOriginAction(frame);

        verifyNoInteractions(actionStack);
        assertTrue(subject.contractActions().isEmpty());
    }

    @Test
    void customInitTracksTopLevel() {
        given(frame.getMessageFrameStack()).willReturn(stack);
        given(stack.isEmpty()).willReturn(true);
        givenSidecarsOnly();

        subject.traceOriginAction(frame);

        verify(actionStack).pushActionOfTopLevel(frame);
    }

    @Test
    void customFinalizeNoopIfNoActionSidecars() {
        given(frame.getMessageFrameStack()).willReturn(stack);
        given(stack.isEmpty()).willReturn(true);
        givenNoActionSidecars();

        subject.sanitizeTracedActions(frame);

        verifyNoInteractions(actionStack);
    }

    @Test
    void customFinalizeNoopIfNotValidatingActions() {
        given(frame.getMessageFrameStack()).willReturn(stack);
        given(stack.isEmpty()).willReturn(true);
        givenSidecarsOnly();

        subject.sanitizeTracedActions(frame);

        verifyNoInteractions(actionStack);
    }

    @Test
    void customFinalizeSanitizesActionsIfValidating() {
        given(frame.getMessageFrameStack()).willReturn(stack);
        given(stack.isEmpty()).willReturn(true);
        givenActionSidecarsAndValidation();

        subject.sanitizeTracedActions(frame);

        verify(actionStack).sanitizeFinalActionsAndLogAnomalies(eq(frame), any(Logger.class), eq(Level.WARN));
    }

    @Test
    void postExecNoopIfCodeExecutingState() {
        // No-op.  API exists for other implementors.
        subject.tracePerOpcode(frame,123,null,null);

        verifyNoInteractions(actionStack);
    }

    @Test
    void postExecTracksIntermediateIfSuspended() {
        given(frame.getMessageFrameStack()).willReturn(stack);
        givenNoActionSidecars();

        MessageFrame child = frame.getMessageFrameStack().peek();
        subject.traceSuspended(frame,child,null);

        verify(actionStack).pushActionOfIntermediate(frame,child,null);
    }

    @Test
    void postExecFinalizesIfNotSuspended() {

        subject.traceNotExecuting(frame);

        verify(actionStack).finalizeLastAction(frame, ActionStack.Validation.OFF);
    }

    @Test
    void precompileTraceIsNoopIfNoSidecars() {
        given(frame.getMessageFrameStack()).willReturn(stack);
        given(stack.isEmpty()).willReturn(true);
        givenNoActionSidecars();

        subject.tracePrecompileResult(frame, ContractActionType.SYSTEM);

        verifyNoInteractions(actionStack);
    }

    @Test
    void systemPrecompileTraceIsStillTrackedEvenIfHalted() {
        given(frame.getMessageFrameStack()).willReturn(stack);
        given(stack.isEmpty()).willReturn(true);
        givenSidecarsOnly();

        subject.tracePrecompileResult(frame, ContractActionType.SYSTEM);

        verify(actionStack)
                .finalizeLastStackActionAsPrecompile(frame, ContractActionType.SYSTEM, ActionStack.Validation.OFF);
    }

    @Test
    void accountCreationTraceIsNoopIfNoSidecars() {
        given(frame.getMessageFrameStack()).willReturn(stack);
        given(stack.isEmpty()).willReturn(true);
        givenNoActionSidecars();

        subject.traceAccountCreationResult(frame, Optional.empty());

        verifyNoInteractions(actionStack);
    }

    @Test
    void accountCreationTraceDoesNotFinalizesEvenWithSidecarsUnlessHaltReasonProvided() {
        given(frame.getMessageFrameStack()).willReturn(stack);
        given(stack.isEmpty()).willReturn(true);
        givenSidecarsOnly();

        subject.traceAccountCreationResult(frame, Optional.empty());

        verifyNoInteractions(actionStack);
    }

    @Test
    void accountCreationTraceFinalizesWithSidecarsAndHaltReason() {
        given(frame.getMessageFrameStack()).willReturn(stack);
        given(stack.isEmpty()).willReturn(true);
        givenActionSidecarsAndValidation();

        subject.traceAccountCreationResult(frame, Optional.of(ExceptionalHaltReason.INSUFFICIENT_GAS));

        verify(actionStack).finalizeLastAction(frame, ActionStack.Validation.ON);
    }

    @Test
    void contractCreationTraceFinalizesWithSidecarsAndHaltReason() {
        given(frame.getMessageFrameStack()).willReturn(stack);
        given(stack.isEmpty()).willReturn(true);
        givenSidecarsOnly();
        given(actionStack.isEmpty()).willReturn(true);
        subject.traceAccountCreationResult(frame, Optional.of(ExceptionalHaltReason.INSUFFICIENT_GAS));

        verify(actionStack, never()).finalizeLastAction(frame, ActionStack.Validation.ON);
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
