/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.service.contract.impl.test.exec.tracers;

import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.CONFIG_CONTEXT_VARIABLE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.streams.ContractActionType;
import com.hedera.node.app.service.contract.impl.exec.tracers.EvmActionTracer;
import com.hedera.node.app.service.contract.impl.exec.utils.ActionStack;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import java.util.Deque;
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
        subject = new EvmActionTracer(actionStack);
    }

    @Test
    void customInitTracksTopLevel() {
        subject.traceOriginAction(frame);

        verify(actionStack).pushActionOfTopLevel(frame);
    }

    @Test
    void customFinalizeNoopIfNotValidatingActions() {
        givenSidecarsValidation(false);

        initialiseStack();

        subject.sanitizeTracedActions(frame);

        verifyNoInteractions(actionStack);
    }

    @Test
    void customFinalizeSanitizesActionsIfValidating() {
        givenSidecarsValidation(true);
        initialiseStack();

        subject.sanitizeTracedActions(frame);

        verify(actionStack).sanitizeFinalActionsAndLogAnomalies(eq(frame), any(Logger.class), eq(Level.WARN));
    }

    @Test
    void postExecNoopIfCodeExecutingState() {
        given(frame.getState()).willReturn(MessageFrame.State.CODE_EXECUTING);

        subject.tracePostExecution(frame, new Operation.OperationResult(123, null));

        verifyNoInteractions(actionStack);
    }

    @Test
    void postExecTracksIntermediateIfSuspended() {
        given(frame.getState()).willReturn(MessageFrame.State.CODE_SUSPENDED);

        subject.tracePostExecution(frame, new Operation.OperationResult(123, null));

        verify(actionStack).pushActionOfIntermediate(frame);
    }

    @Test
    void postExecFinalizesIfNotSuspended() {
        givenSidecarsValidation(true);
        initialiseStack();

        given(frame.getState()).willReturn(MessageFrame.State.COMPLETED_SUCCESS);

        subject.tracePostExecution(frame, new Operation.OperationResult(123, null));

        verify(actionStack).finalizeLastAction(frame, ActionStack.Validation.ON);
    }

    @Test
    void systemPrecompileTraceIsStillTrackedEvenIfHalted() {
        givenSidecarsValidation(false);
        initialiseStack();

        subject.tracePrecompileResult(frame, ContractActionType.SYSTEM);

        verify(actionStack)
                .finalizeLastStackActionAsPrecompile(frame, ContractActionType.SYSTEM, ActionStack.Validation.OFF);
    }

    @Test
    void accountCreationTraceDoesNotFinalizesEvenWithSidecarsUnlessHaltReasonProvided() {
        subject.traceAccountCreationResult(frame, Optional.empty());

        verifyNoInteractions(actionStack);
    }

    @Test
    void accountCreationTraceFinalizesWithSidecarsAndHaltReason() {
        givenSidecarsValidation(true);
        initialiseStack();

        subject.traceAccountCreationResult(frame, Optional.of(ExceptionalHaltReason.INSUFFICIENT_GAS));

        verify(actionStack).finalizeLastAction(frame, ActionStack.Validation.ON);
    }

    private void givenSidecarsValidation(final boolean validation) {
        final var config = HederaTestConfigBuilder.create()
                .withValue("contracts.sidecarValidationEnabled", validation ? "true" : "false")
                .getOrCreateConfig();
        given(frame.getContextVariable(CONFIG_CONTEXT_VARIABLE)).willReturn(config);
    }

    private void initialiseStack() {
        given(frame.getMessageFrameStack()).willReturn(stack);
        given(stack.isEmpty()).willReturn(true);
    }
}
