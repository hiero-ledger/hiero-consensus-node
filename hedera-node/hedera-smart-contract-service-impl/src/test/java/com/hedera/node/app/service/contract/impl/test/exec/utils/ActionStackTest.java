// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.utils;

import static com.hedera.hapi.streams.CallOperationType.OP_CALL;
import static com.hedera.hapi.streams.CallOperationType.OP_CREATE;
import static com.hedera.hapi.streams.ContractActionType.CALL;
import static com.hedera.hapi.streams.ContractActionType.CREATE;
import static com.hedera.hapi.streams.ContractActionType.PRECOMPILE;
import static com.hedera.hapi.streams.ContractActionType.SYSTEM;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.INVALID_SOLIDITY_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.INVALID_ADDRESS_CONTEXT_VARIABLE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALLED_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALLED_EOA_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALL_ACTION;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALL_DATA;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CONTRACT_CODE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CREATE_ACTION;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.EIP_1014_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.HTS_SYSTEM_CONTRACT_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.LAZY_CREATE_ACTION;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.MISSING_ADDRESS_CALL_ACTION;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_LONG_ZERO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OUTPUT_DATA;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.REMAINING_GAS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SOME_REVERT_REASON;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.STACK_DEPTH;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.VALUE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.WEI_VALUE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.pbjToTuweniBytes;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.tuweniToPbjBytes;
import static org.hyperledger.besu.evm.frame.ExceptionalHaltReason.ILLEGAL_STATE_CHANGE;
import static org.hyperledger.besu.evm.frame.MessageFrame.State.CODE_SUCCESS;
import static org.hyperledger.besu.evm.frame.MessageFrame.Type.CONTRACT_CREATION;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.streams.ContractAction;
import com.hedera.hapi.streams.ContractActionType;
import com.hedera.node.app.service.contract.impl.exec.utils.ActionStack;
import com.hedera.node.app.service.contract.impl.exec.utils.ActionWrapper;
import com.hedera.node.app.service.contract.impl.exec.utils.ActionsHelper;
import com.hedera.node.app.service.contract.impl.exec.utils.InvalidAddressContext;
import com.hedera.node.app.service.contract.impl.state.HederaEvmAccount;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogBuilder;
import org.apache.logging.log4j.Logger;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.code.CodeV0;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.operation.Operation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ActionStackTest {
    @Mock
    private Account account;

    @Mock
    private HederaEvmAccount evmAccount;

    @Mock
    private Operation operation;

    @Mock
    private ActionsHelper helper;

    @Mock
    private MessageFrame childFrame;

    @Mock
    private MessageFrame parentFrame;

    @Mock
    private ProxyWorldUpdater worldUpdater;

    @Mock
    private Deque<MessageFrame> frameStack;

    @Mock
    private Logger log;

    @Mock
    private LogBuilder logBuilder;

    private final List<ActionWrapper> invalidActions = new ArrayList<>();
    private final List<ActionWrapper> allActions = new ArrayList<>();
    private final Deque<ActionWrapper> actionsStack = new ArrayDeque<>();

    private ActionStack subject;

    @BeforeEach
    void setUp() {
        subject = new ActionStack(helper, allActions, actionsStack, invalidActions);
    }

    @Test
    void loggingAnomaliesIsNoopWithEmptyStackAndNoInvalid() {
        subject.sanitizeFinalActionsAndLogAnomalies(parentFrame, log, Level.ERROR);
        verifyNoInteractions(log);
    }

    @Test
    void getsActionsFromWrappers() {
        allActions.add(new ActionWrapper(ContractAction.DEFAULT));
        allActions.add(new ActionWrapper(ContractAction.DEFAULT));
        final var actions = subject.asContractActions();
        assertEquals(actions, List.of(ContractAction.DEFAULT, ContractAction.DEFAULT));
    }

    @Test
    void logsAndSanitizesInvalidAsExpected() {
        given(log.atLevel(Level.ERROR)).willReturn(logBuilder);
        final var contextCaptor = forClass(String.class);
        final var anomaliesCaptor = forClass(String.class);
        final var invalidWrapper = new ActionWrapper(MISSING_ADDRESS_CALL_ACTION);
        invalidActions.add(invalidWrapper);
        allActions.add(invalidWrapper);
        given(parentFrame.getOriginatorAddress()).willReturn(NON_SYSTEM_LONG_ZERO_ADDRESS);
        given(parentFrame.getSenderAddress()).willReturn(EIP_1014_ADDRESS);
        given(parentFrame.getRecipientAddress()).willReturn(EIP_1014_ADDRESS);
        given(parentFrame.getContractAddress()).willReturn(HTS_SYSTEM_CONTRACT_ADDRESS);
        given(parentFrame.getType()).willReturn(MessageFrame.Type.MESSAGE_CALL);
        given(parentFrame.getState()).willReturn(MessageFrame.State.COMPLETED_FAILED);
        given(helper.prettyPrint(MISSING_ADDRESS_CALL_ACTION)).willReturn("<pretty-printed action>");

        subject.sanitizeFinalActionsAndLogAnomalies(parentFrame, log, Level.ERROR);

        verify(logBuilder)
                .log(eq("Invalid at end of EVM run: {} ({})"), anomaliesCaptor.capture(), contextCaptor.capture());
        final var expectedAnomalies = "of 1 actions given, 1 were invalid; invalid: <pretty-printed action>";
        assertEquals(expectedAnomalies, anomaliesCaptor.getValue());
        final var expectedContext =
                "originator 0000000000000000000000000000001234576890 sender 89abcdef89abcdef89abcdef89abcdef89abcdef recipient 89abcdef89abcdef89abcdef89abcdef89abcdef contract 0000000000000000000000000000000000000167 type MESSAGE_CALL state COMPLETED_FAILED";
        assertEquals(expectedContext, contextCaptor.getValue());

        assertTrue(invalidActions.isEmpty());
        assertTrue(allActions.isEmpty());
    }

    @Test
    void logsIfStackNotEmpty() {
        given(log.atLevel(Level.ERROR)).willReturn(logBuilder);
        final var contextCaptor = forClass(String.class);
        final var anomaliesCaptor = forClass(String.class);
        final var invalidWrapper = new ActionWrapper(MISSING_ADDRESS_CALL_ACTION);
        actionsStack.push(invalidWrapper);
        given(parentFrame.getOriginatorAddress()).willReturn(NON_SYSTEM_LONG_ZERO_ADDRESS);
        given(parentFrame.getSenderAddress()).willReturn(EIP_1014_ADDRESS);
        given(parentFrame.getRecipientAddress()).willReturn(EIP_1014_ADDRESS);
        given(parentFrame.getContractAddress()).willReturn(HTS_SYSTEM_CONTRACT_ADDRESS);
        given(parentFrame.getType()).willReturn(MessageFrame.Type.MESSAGE_CALL);
        given(parentFrame.getState()).willReturn(MessageFrame.State.COMPLETED_FAILED);

        subject.sanitizeFinalActionsAndLogAnomalies(parentFrame, log, Level.ERROR);

        verify(logBuilder)
                .log(eq("Invalid at end of EVM run: {} ({})"), anomaliesCaptor.capture(), contextCaptor.capture());
        final var expectedAnomalies = "currentActionsStack not empty, has 1 elements left";
        assertEquals(expectedAnomalies, anomaliesCaptor.getValue());
        final var expectedContext =
                "originator 0000000000000000000000000000001234576890 sender 89abcdef89abcdef89abcdef89abcdef89abcdef recipient 89abcdef89abcdef89abcdef89abcdef89abcdef contract 0000000000000000000000000000000000000167 type MESSAGE_CALL state COMPLETED_FAILED";
        assertEquals(expectedContext, contextCaptor.getValue());
    }

    @Test
    void withoutHaltReasonJustUsesEmptyErrorAndNullsCalledContract() {
        given(parentFrame.getState()).willReturn(MessageFrame.State.EXCEPTIONAL_HALT);
        final var wrappedAction = new ActionWrapper(CREATE_ACTION);
        allActions.add(wrappedAction);
        actionsStack.push(wrappedAction);
        given(parentFrame.getType()).willReturn(CONTRACT_CREATION);

        subject.finalizeLastAction(parentFrame, ActionStack.Validation.OFF);

        assertEquals(1, allActions.size());
        assertEquals(wrappedAction, allActions.get(0));
        final var finalAction = wrappedAction.get();
        assertNull(finalAction.recipientContract());
        assertEquals(REMAINING_GAS, finalAction.gasUsed());
        assertSame(Bytes.EMPTY, finalAction.error());
        assertTrue(actionsStack.isEmpty());
    }

    @Test
    void configuresPrecompileActionAsExpected() {
        given(parentFrame.getState()).willReturn(MessageFrame.State.EXCEPTIONAL_HALT);
        final var pretendPrecompileAction = CALL_ACTION
                .copyBuilder()
                .targetedAddress(tuweniToPbjBytes(HTS_SYSTEM_CONTRACT_ADDRESS))
                .build();
        final var wrappedAction = new ActionWrapper(pretendPrecompileAction);
        allActions.add(wrappedAction);
        actionsStack.push(wrappedAction);
        given(parentFrame.getType()).willReturn(MessageFrame.Type.MESSAGE_CALL);
        given(parentFrame.getContractAddress()).willReturn(HTS_SYSTEM_CONTRACT_ADDRESS);
        given(parentFrame.getWorldUpdater()).willReturn(worldUpdater);
        given(worldUpdater.entityIdFactory()).willReturn(entityIdFactory);

        subject.finalizeLastStackActionAsPrecompile(parentFrame, SYSTEM, ActionStack.Validation.ON);

        assertEquals(1, allActions.size());
        assertEquals(wrappedAction, allActions.get(0));
        final var finalAction = wrappedAction.get();
        assertEquals(
                ContractID.newBuilder()
                        .contractNum(ConversionUtils.numberOfLongZero(HTS_SYSTEM_CONTRACT_ADDRESS))
                        .build(),
                finalAction.recipientContract());
        assertEquals(SYSTEM, finalAction.callType());
        assertTrue(actionsStack.isEmpty());
        assertEquals(1, invalidActions.size());
        assertSame(finalAction, invalidActions.get(0).get());
        assertEquals(wrappedAction, invalidActions.get(0));
    }

    @Test
    void doesNotAddValidActionToInvalidList() {
        given(parentFrame.getState()).willReturn(MessageFrame.State.COMPLETED_FAILED);
        final var pretendPrecompileAction = CALL_ACTION
                .copyBuilder()
                .targetedAddress(tuweniToPbjBytes(HTS_SYSTEM_CONTRACT_ADDRESS))
                .build();
        final var wrappedAction = new ActionWrapper(pretendPrecompileAction);
        allActions.add(wrappedAction);
        actionsStack.push(wrappedAction);
        given(parentFrame.getType()).willReturn(MessageFrame.Type.MESSAGE_CALL);
        given(parentFrame.getContractAddress()).willReturn(HTS_SYSTEM_CONTRACT_ADDRESS);
        given(parentFrame.getWorldUpdater()).willReturn(worldUpdater);
        given(helper.isValid(any())).willReturn(true);
        given(worldUpdater.entityIdFactory()).willReturn(entityIdFactory);

        subject.finalizeLastStackActionAsPrecompile(parentFrame, PRECOMPILE, ActionStack.Validation.ON);

        assertEquals(1, allActions.size());
        assertEquals(wrappedAction, allActions.get(0));
        final var finalAction = wrappedAction.get();
        assertEquals(
                ContractID.newBuilder()
                        .contractNum(ConversionUtils.numberOfLongZero(HTS_SYSTEM_CONTRACT_ADDRESS))
                        .build(),
                finalAction.recipientContract());
        assertEquals(PRECOMPILE, finalAction.callType());
        assertTrue(actionsStack.isEmpty());
        assertTrue(invalidActions.isEmpty());
    }

    @Test
    void worksAroundEmptyStack() {
        final var wrappedAction = new ActionWrapper(CALL_ACTION);
        allActions.add(wrappedAction);

        assertDoesNotThrow(() -> subject.finalizeLastAction(parentFrame, ActionStack.Validation.OFF));
    }

    @Test
    void worksAroundEmptyList() {
        assertDoesNotThrow(() -> subject.finalizeLastAction(parentFrame, ActionStack.Validation.OFF));
    }

    @Test
    void withNonMissingHaltReasonJustSetsIt() {
        given(parentFrame.getState()).willReturn(MessageFrame.State.EXCEPTIONAL_HALT);
        final var wrappedAction = new ActionWrapper(CALL_ACTION);
        allActions.add(wrappedAction);
        actionsStack.push(wrappedAction);
        given(parentFrame.getType()).willReturn(MessageFrame.Type.MESSAGE_CALL);
        given(parentFrame.getExceptionalHaltReason()).willReturn(Optional.of(ILLEGAL_STATE_CHANGE));

        subject.finalizeLastAction(parentFrame, ActionStack.Validation.OFF);

        assertEquals(1, allActions.size());
        assertEquals(wrappedAction, allActions.get(0));
        final var finalAction = wrappedAction.get();
        assertEquals(REMAINING_GAS, finalAction.gasUsed());
        assertEquals(Bytes.wrap(ILLEGAL_STATE_CHANGE.toString()), finalAction.error());
        assertTrue(actionsStack.isEmpty());
    }

    @Test
    void withInvalidSolidityAddressHaltReasonAddsSyntheticInvalidActionToCall() {
        given(parentFrame.getState()).willReturn(MessageFrame.State.EXCEPTIONAL_HALT);
        final var wrappedAction = new ActionWrapper(CALL_ACTION);
        allActions.add(wrappedAction);
        actionsStack.push(wrappedAction);
        given(parentFrame.getType()).willReturn(MessageFrame.Type.MESSAGE_CALL);
        given(parentFrame.getExceptionalHaltReason()).willReturn(Optional.of(INVALID_SOLIDITY_ADDRESS));
        final var invalidAddressContext = new InvalidAddressContext();
        invalidAddressContext.set(Address.ZERO, InvalidAddressContext.InvalidAddressType.InvalidCallTarget);
        given(parentFrame.getMessageFrameStack()).willReturn(new ArrayDeque<>());
        given(parentFrame.getContextVariable(INVALID_ADDRESS_CONTEXT_VARIABLE)).willReturn(invalidAddressContext);
        given(helper.createSynthActionForMissingAddressIn(parentFrame, Address.ZERO))
                .willReturn(MISSING_ADDRESS_CALL_ACTION);

        subject.finalizeLastAction(parentFrame, ActionStack.Validation.OFF);

        assertEquals(2, allActions.size());
        assertEquals(wrappedAction, allActions.get(0));
        final var finalAction = wrappedAction.get();
        assertEquals(REMAINING_GAS, finalAction.gasUsed());
        final var syntheticWrappedAction = allActions.get(1);
        final var syntheticAction = syntheticWrappedAction.get();
        assertSame(MISSING_ADDRESS_CALL_ACTION, syntheticAction);
    }

    @Test
    void withInvalidSolidityAddressHaltReasonDoesNotAddSyntheticInvalidActionToCreate() {
        given(parentFrame.getState()).willReturn(MessageFrame.State.EXCEPTIONAL_HALT);
        final var wrappedAction = new ActionWrapper(CREATE_ACTION);
        allActions.add(wrappedAction);
        actionsStack.push(wrappedAction);
        given(parentFrame.getType()).willReturn(CONTRACT_CREATION);
        given(parentFrame.getExceptionalHaltReason()).willReturn(Optional.of(INVALID_SOLIDITY_ADDRESS));

        subject.finalizeLastAction(parentFrame, ActionStack.Validation.OFF);

        assertEquals(1, allActions.size());
        assertEquals(wrappedAction, allActions.get(0));
        final var finalAction = wrappedAction.get();
        assertEquals(REMAINING_GAS, finalAction.gasUsed());
    }

    @ParameterizedTest
    @CsvSource({"NOT_STARTED", "CODE_EXECUTING", "CODE_SUSPENDED"})
    void finalizationDoesNothingButMaybePopStackForUnexpectedStates(@NonNull final MessageFrame.State state) {
        given(parentFrame.getState()).willReturn(state);
        final var wrappedAction = new ActionWrapper(CALL_ACTION);
        allActions.add(wrappedAction);
        actionsStack.push(wrappedAction);

        subject.finalizeLastAction(parentFrame, ActionStack.Validation.OFF);

        assertEquals(1, allActions.size());
        assertEquals(wrappedAction, allActions.get(0));
        assertEquals(CALL_ACTION, allActions.get(0).get());
        assertTrue(actionsStack.isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
        "COMPLETED_SUCCESS,CREATE,false",
        "CODE_SUCCESS,CREATE,false",
        "COMPLETED_SUCCESS,CALL,false",
        "CODE_SUCCESS,CALL,false",
        "COMPLETED_SUCCESS,CALL,true",
        "CODE_SUCCESS,CALL,true",
    })
    void finalizationWorksAsExpectedForSuccessfulLazyCreatesAndNormalCalls(
            @NonNull final MessageFrame.State state,
            @NonNull final ContractActionType type,
            final boolean isLazyCreation) {
        given(parentFrame.getState()).willReturn(state);
        final var gasUsed = REMAINING_GAS / 3;
        given(parentFrame.getRemainingGas()).willReturn(REMAINING_GAS - gasUsed);
        if (type == CALL) {
            given(parentFrame.getOutputData()).willReturn(pbjToTuweniBytes(OUTPUT_DATA));
        }
        final var action = (type == CREATE) ? CREATE_ACTION : (isLazyCreation ? LAZY_CREATE_ACTION : CALL_ACTION);
        if (isLazyCreation) {
            givenResolvableEvmAddress();
        }

        final var wrappedAction = new ActionWrapper(action);
        allActions.add(wrappedAction);
        actionsStack.push(wrappedAction);

        subject.finalizeLastAction(parentFrame, ActionStack.Validation.OFF);

        assertEquals(1, allActions.size());
        assertEquals(wrappedAction, allActions.get(0));
        final var finalAction = allActions.get(0).get();
        assertEquals(gasUsed, finalAction.gasUsed());
        if (type == CREATE) {
            assertEquals(Bytes.EMPTY, finalAction.output());
        } else {
            assertEquals(OUTPUT_DATA, finalAction.output());
            if (isLazyCreation) {
                assertNull(finalAction.targetedAddress());
                assertEquals(CALLED_EOA_ID, finalAction.recipientAccount());
            }
        }
        assertTrue(actionsStack.isEmpty());
    }

    @Test
    void finalizationLazyCallWithUnresolvedAddress() {
        given(parentFrame.getState()).willReturn(CODE_SUCCESS);
        final var gasUsed = REMAINING_GAS / 3;
        given(parentFrame.getRemainingGas()).willReturn(REMAINING_GAS - gasUsed);
        given(parentFrame.getOutputData()).willReturn(pbjToTuweniBytes(OUTPUT_DATA));
        given(parentFrame.getWorldUpdater()).willReturn(worldUpdater);

        final var wrappedAction = new ActionWrapper(LAZY_CREATE_ACTION);
        allActions.add(wrappedAction);
        actionsStack.push(wrappedAction);

        subject.finalizeLastAction(parentFrame, ActionStack.Validation.OFF);

        assertEquals(1, allActions.size());
        assertEquals(wrappedAction, allActions.get(0));
        final var finalAction = allActions.get(0).get();
        assertEquals(gasUsed, finalAction.gasUsed());
        assertNull(finalAction.recipientAccount());
        assertEquals(tuweniToPbjBytes(EIP_1014_ADDRESS), finalAction.targetedAddress());
        assertTrue(actionsStack.isEmpty());
    }

    @Test
    void revertReasonPreservedIfPresentAndCreationRecipientNulledOut() {
        given(parentFrame.getState()).willReturn(MessageFrame.State.REVERT);
        final var gasUsed = REMAINING_GAS / 3;
        given(parentFrame.getRemainingGas()).willReturn(REMAINING_GAS - gasUsed);
        given(parentFrame.getRevertReason()).willReturn(Optional.of(SOME_REVERT_REASON));
        given(parentFrame.getType()).willReturn(CONTRACT_CREATION);

        final var wrappedAction = new ActionWrapper(CREATE_ACTION);
        allActions.add(wrappedAction);
        actionsStack.push(wrappedAction);

        subject.finalizeLastAction(parentFrame, ActionStack.Validation.OFF);

        assertEquals(1, allActions.size());
        assertEquals(wrappedAction, allActions.get(0));
        final var finalAction = allActions.get(0).get();
        assertEquals(gasUsed, finalAction.gasUsed());
        assertNull(finalAction.recipientContract());
        assertEquals(tuweniToPbjBytes(SOME_REVERT_REASON), finalAction.revertReason());
    }

    @Test
    void emptyRevertReasonUsedIfMissing() {
        given(parentFrame.getState()).willReturn(MessageFrame.State.REVERT);
        final var gasUsed = REMAINING_GAS / 3;
        given(parentFrame.getRemainingGas()).willReturn(REMAINING_GAS - gasUsed);
        given(parentFrame.getType()).willReturn(CONTRACT_CREATION);

        final var wrappedAction = new ActionWrapper(CALL_ACTION);
        allActions.add(wrappedAction);
        actionsStack.push(wrappedAction);

        subject.finalizeLastAction(parentFrame, ActionStack.Validation.OFF);

        assertEquals(1, allActions.size());
        assertEquals(wrappedAction, allActions.get(0));
        final var finalAction = allActions.get(0).get();
        assertEquals(gasUsed, finalAction.gasUsed());
        assertEquals(Bytes.EMPTY, finalAction.revertReason());
    }

    @Test
    void tracksTopLevelCreationAsExpected() {
        givenPresentEvmAddress();

        given(parentFrame.getType()).willReturn(CONTRACT_CREATION);
        given(parentFrame.getOriginatorAddress()).willReturn(NON_SYSTEM_LONG_ZERO_ADDRESS);
        given(parentFrame.getRemainingGas()).willReturn(REMAINING_GAS);
        given(parentFrame.getInputData()).willReturn(pbjToTuweniBytes(CALL_DATA));
        given(parentFrame.getValue()).willReturn(WEI_VALUE);
        given(parentFrame.getDepth()).willReturn(STACK_DEPTH);
        given(parentFrame.getCode()).willReturn(CONTRACT_CODE);
        given(parentFrame.getContractAddress()).willReturn(EIP_1014_ADDRESS);
        given(worldUpdater.entityIdFactory()).willReturn(entityIdFactory);

        subject.pushActionOfTopLevel(parentFrame);

        assertEquals(1, allActions.size());
        assertEquals(1, actionsStack.size());
        final var action = actionsStack.peek().get();
        assertSame(action, allActions.get(0).get());

        assertEquals(CREATE, action.callType());
        assertEquals(OP_CREATE, action.callOperationType());
        assertEquals(REMAINING_GAS, action.gas());
        assertEquals(CALL_DATA, action.input());
        assertEquals(VALUE, action.value());
        assertEquals(STACK_DEPTH, action.callDepth());
        assertEquals(CALLED_CONTRACT_ID, action.recipientContract());
        assertEquals(NON_SYSTEM_ACCOUNT_ID, action.callingAccount());
    }

    @Test
    void tracksTopLevelCallToEoaAsExpected() {
        givenPresentEvmAddress();

        given(worldUpdater.get(EIP_1014_ADDRESS)).willReturn(account);
        given(parentFrame.getType()).willReturn(MessageFrame.Type.MESSAGE_CALL);
        given(parentFrame.getOriginatorAddress()).willReturn(NON_SYSTEM_LONG_ZERO_ADDRESS);
        given(parentFrame.getRemainingGas()).willReturn(REMAINING_GAS);
        given(parentFrame.getInputData()).willReturn(pbjToTuweniBytes(CALL_DATA));
        given(parentFrame.getValue()).willReturn(WEI_VALUE);
        given(parentFrame.getDepth()).willReturn(STACK_DEPTH);
        given(parentFrame.getCode()).willReturn(CodeV0.EMPTY_CODE);
        given(parentFrame.getContractAddress()).willReturn(EIP_1014_ADDRESS);
        given(worldUpdater.entityIdFactory()).willReturn(entityIdFactory);

        subject.pushActionOfTopLevel(parentFrame);

        assertEquals(1, allActions.size());
        assertEquals(1, actionsStack.size());
        final var action = actionsStack.peek().get();
        assertSame(action, allActions.get(0).get());

        assertEquals(CALL, action.callType());
        assertEquals(OP_CALL, action.callOperationType());
        assertEquals(REMAINING_GAS, action.gas());
        assertEquals(CALL_DATA, action.input());
        assertEquals(VALUE, action.value());
        assertEquals(STACK_DEPTH, action.callDepth());
        assertEquals(CALLED_EOA_ID, action.recipientAccount());
        assertEquals(NON_SYSTEM_ACCOUNT_ID, action.callingAccount());
    }

    @Test
    void tracksTopLevelCallToMissingAsExpected() {
        given(parentFrame.getType()).willReturn(MessageFrame.Type.MESSAGE_CALL);
        given(parentFrame.getOriginatorAddress()).willReturn(NON_SYSTEM_LONG_ZERO_ADDRESS);
        given(parentFrame.getRemainingGas()).willReturn(REMAINING_GAS);
        given(parentFrame.getInputData()).willReturn(pbjToTuweniBytes(CALL_DATA));
        given(parentFrame.getValue()).willReturn(WEI_VALUE);
        given(parentFrame.getDepth()).willReturn(STACK_DEPTH);
        given(parentFrame.getContractAddress()).willReturn(EIP_1014_ADDRESS);
        given(parentFrame.getWorldUpdater()).willReturn(worldUpdater);
        given(worldUpdater.entityIdFactory()).willReturn(entityIdFactory);

        subject.pushActionOfTopLevel(parentFrame);

        assertEquals(1, allActions.size());
        assertEquals(1, actionsStack.size());
        final var action = actionsStack.peek().get();
        assertSame(action, allActions.get(0).get());

        assertEquals(CALL, action.callType());
        assertEquals(OP_CALL, action.callOperationType());
        assertEquals(REMAINING_GAS, action.gas());
        assertEquals(CALL_DATA, action.input());
        assertEquals(VALUE, action.value());
        assertEquals(STACK_DEPTH, action.callDepth());
        assertNull(action.recipientAccount());
        assertEquals(tuweniToPbjBytes(EIP_1014_ADDRESS), action.targetedAddress());
        assertEquals(NON_SYSTEM_ACCOUNT_ID, action.callingAccount());
    }

    @Test
    void tracksIntermediateCallAsExpected() {
        given(operation.getOpcode()).willReturn(0xF1);
        given(parentFrame.getCurrentOperation()).willReturn(operation);
        given(parentFrame.getMessageFrameStack()).willReturn(frameStack);
        given(parentFrame.getContractAddress()).willReturn(NON_SYSTEM_LONG_ZERO_ADDRESS);
        given(parentFrame.getWorldUpdater()).willReturn(worldUpdater);
        given(frameStack.peek()).willReturn(childFrame);

        given(childFrame.getType()).willReturn(MessageFrame.Type.MESSAGE_CALL);
        given(childFrame.getRemainingGas()).willReturn(REMAINING_GAS);
        given(childFrame.getInputData()).willReturn(pbjToTuweniBytes(CALL_DATA));
        given(childFrame.getValue()).willReturn(WEI_VALUE);
        given(childFrame.getDepth()).willReturn(STACK_DEPTH);
        given(childFrame.getContractAddress()).willReturn(EIP_1014_ADDRESS);
        given(childFrame.getWorldUpdater()).willReturn(worldUpdater);
        given(worldUpdater.get(EIP_1014_ADDRESS)).willReturn(account);
        given(worldUpdater.getHederaContractId(EIP_1014_ADDRESS)).willReturn(CALLED_CONTRACT_ID);
        given(worldUpdater.entityIdFactory()).willReturn(entityIdFactory);

        subject.pushActionOfIntermediate(parentFrame);

        assertEquals(1, allActions.size());
        assertEquals(1, actionsStack.size());
        final var action = actionsStack.peek().get();
        assertSame(action, allActions.get(0).get());

        assertEquals(CALL, action.callType());
        assertEquals(OP_CALL, action.callOperationType());
        assertEquals(REMAINING_GAS, action.gas());
        assertEquals(CALL_DATA, action.input());
        assertEquals(VALUE, action.value());
        assertEquals(STACK_DEPTH, action.callDepth());
        assertEquals(CALLED_CONTRACT_ID, action.recipientContract());
        assertEquals(NON_SYSTEM_CONTRACT_ID, action.callingContract());
    }

    private void givenResolvableEvmAddress() {
        given(parentFrame.getWorldUpdater()).willReturn(worldUpdater);
        given(worldUpdater.getHederaAccount(EIP_1014_ADDRESS)).willReturn(evmAccount);
        given(evmAccount.hederaId())
                .willReturn(AccountID.newBuilder()
                        .accountNum(CALLED_CONTRACT_ID.contractNumOrThrow())
                        .build());
    }

    private void givenPresentEvmAddress() {
        given(parentFrame.getWorldUpdater()).willReturn(worldUpdater);
        given(worldUpdater.getHederaContractId(EIP_1014_ADDRESS)).willReturn(CALLED_CONTRACT_ID);
    }
}
