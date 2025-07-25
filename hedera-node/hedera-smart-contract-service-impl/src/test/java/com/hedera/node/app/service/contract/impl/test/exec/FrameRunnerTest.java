// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec;

import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.FAILURE_DURING_LAZY_ACCOUNT_CREATION;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.INSUFFICIENT_CHILD_RECORDS;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.INVALID_SIGNATURE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.BESU_LOG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.BESU_MAX_REFUND_QUOTIENT;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALLED_CONTRACT_EVM_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALLED_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.EIP_1014_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.GAS_LIMIT;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.HEDERA_MAX_REFUND_PERCENTAGE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NETWORK_GAS_PRICE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_LONG_ZERO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OUTPUT_DATA;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SOME_REVERT_REASON;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asEvmContractId;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.numberOfLongZero;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.pbjToTuweniBytes;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.tuweniToPbjBytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.node.app.service.contract.impl.exec.ActionSidecarContentTracer;
import com.hedera.node.app.service.contract.impl.exec.FrameRunner;
import com.hedera.node.app.service.contract.impl.exec.gas.CustomGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.processors.CustomMessageCallProcessor;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.exec.utils.PropagatedCallFailureRef;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransactionResult;
import com.hedera.node.app.service.contract.impl.hevm.HevmPropagatedCallFailure;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.spi.workflows.ResourceExhaustedException;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.EntityIdFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.frame.MessageFrame.Type;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.processor.ContractCreationProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FrameRunnerTest {

    private static final long EXPECTED_GAS_USED_NO_REFUNDS = 400000;

    @Mock
    private MessageFrame frame;

    @Mock
    private MessageFrame lasMessageFrame;

    @Mock
    private ProxyWorldUpdater worldUpdater;

    @Mock
    private MessageFrame childFrame;

    @Mock
    private ActionSidecarContentTracer tracer;

    @Mock
    private CustomMessageCallProcessor messageCallProcessor;

    @Mock
    private ContractCreationProcessor contractCreationProcessor;

    @Mock
    private CustomGasCalculator gasCalculator;

    @Mock
    private EntityIdFactory entityIdFactory;

    @Mock
    final Deque<MessageFrame> mockedStack = new ArrayDeque<>();

    private final PropagatedCallFailureRef propagatedCallFailure = new PropagatedCallFailureRef();

    private FrameRunner subject;

    @BeforeEach
    void setUp() {
        subject = new FrameRunner(gasCalculator, entityIdFactory);
    }

    @Test
    void happyPathWorksWithEip1014Receiver() {
        final var inOrder = Mockito.inOrder(frame, childFrame, tracer, messageCallProcessor, contractCreationProcessor);

        givenBaseSuccessWith(EIP_1014_ADDRESS);
        given(frame.getWorldUpdater()).willReturn(worldUpdater);
        given(worldUpdater.getHederaContractId(EIP_1014_ADDRESS)).willReturn(CALLED_CONTRACT_ID);
        final var contractId = ContractID.newBuilder()
                .evmAddress(Bytes.wrap(EIP_1014_ADDRESS.toArray()))
                .build();
        given(entityIdFactory.newContractIdWithEvmAddress(any())).willReturn(contractId);

        // Gas refund setup
        final var nominalRefund = 500_000L;
        final var nominalGasUsed = 500_000L;
        final var expectedGasUsedPostRefunds = nominalGasUsed - nominalRefund / BESU_MAX_REFUND_QUOTIENT;
        given(frame.getRemainingGas()).willReturn(GAS_LIMIT - nominalGasUsed);
        given(frame.getGasRefund()).willReturn(nominalRefund);

        final var result = subject.runToCompletion(
                GAS_LIMIT, SENDER_ID, frame, tracer, messageCallProcessor, contractCreationProcessor);

        inOrder.verify(tracer).traceOriginAction(frame);
        inOrder.verify(contractCreationProcessor).process(frame, tracer);
        inOrder.verify(messageCallProcessor).process(childFrame, tracer);
        inOrder.verify(tracer).sanitizeTracedActions(frame);

        assertTrue(result.isSuccess());
        assertEquals(expectedGasUsedPostRefunds, result.gasUsed());
        assertEquals(List.of(BESU_LOG), result.logs());
        assertEquals(CALLED_CONTRACT_ID, result.recipientId());
        assertEquals(CALLED_CONTRACT_EVM_ADDRESS, result.recipientEvmAddress());

        assertSuccessExpectationsWith(CALLED_CONTRACT_ID, CALLED_CONTRACT_EVM_ADDRESS, result);
    }

    @Test
    void happyPathWorksWithLongZeroReceiver() {
        final var inOrder = Mockito.inOrder(frame, childFrame, tracer, messageCallProcessor, contractCreationProcessor);

        givenBaseSuccessWith(NON_SYSTEM_LONG_ZERO_ADDRESS);
        final var contractId = ContractID.newBuilder()
                .contractNum(numberOfLongZero(NON_SYSTEM_LONG_ZERO_ADDRESS))
                .build();
        given(entityIdFactory.newContractIdWithEvmAddress(any())).willReturn(contractId);
        given(entityIdFactory.newContractId(numberOfLongZero(NON_SYSTEM_LONG_ZERO_ADDRESS)))
                .willReturn(contractId);
        final var result = subject.runToCompletion(
                GAS_LIMIT, SENDER_ID, frame, tracer, messageCallProcessor, contractCreationProcessor);

        inOrder.verify(tracer).traceOriginAction(frame);
        assertEquals(EXPECTED_GAS_USED_NO_REFUNDS, result.gasUsed());
        inOrder.verify(contractCreationProcessor).process(frame, tracer);
        inOrder.verify(messageCallProcessor).process(childFrame, tracer);
        inOrder.verify(tracer).sanitizeTracedActions(frame);

        assertSuccessExpectationsWith(
                NON_SYSTEM_CONTRACT_ID, asEvmContractId(entityIdFactory, NON_SYSTEM_LONG_ZERO_ADDRESS), result);
    }

    @Test
    void failurePathWorksWithRevertReason() {
        final var inOrder = Mockito.inOrder(frame, childFrame, tracer, messageCallProcessor, contractCreationProcessor);

        givenBaseFailureWith(NON_SYSTEM_LONG_ZERO_ADDRESS);
        given(frame.getRevertReason()).willReturn(Optional.of(SOME_REVERT_REASON));
        final var contractId = ContractID.newBuilder()
                .contractNum(numberOfLongZero(NON_SYSTEM_LONG_ZERO_ADDRESS))
                .build();
        given(entityIdFactory.newContractId(numberOfLongZero(NON_SYSTEM_LONG_ZERO_ADDRESS)))
                .willReturn(contractId);

        final var result = subject.runToCompletion(
                GAS_LIMIT, SENDER_ID, frame, tracer, messageCallProcessor, contractCreationProcessor);

        inOrder.verify(tracer).traceOriginAction(frame);
        inOrder.verify(contractCreationProcessor).process(frame, tracer);
        inOrder.verify(messageCallProcessor).process(childFrame, tracer);
        inOrder.verify(tracer).sanitizeTracedActions(frame);

        assertFailureExpectationsWith(frame, result);
        assertEquals(tuweniToPbjBytes(SOME_REVERT_REASON), result.revertReason());
        assertNull(result.haltReason());
    }

    @Test
    void failurePathWorksWithMissingReceiverSigHalt() {
        final var inOrder = Mockito.inOrder(frame, childFrame, tracer, messageCallProcessor, contractCreationProcessor);

        givenBaseReceiverSigCheckHaltWith(NON_SYSTEM_LONG_ZERO_ADDRESS);
        given(frame.getExceptionalHaltReason()).willReturn(Optional.of(INVALID_SIGNATURE));
        final var contractId = ContractID.newBuilder()
                .contractNum(numberOfLongZero(NON_SYSTEM_LONG_ZERO_ADDRESS))
                .build();
        given(entityIdFactory.newContractId(numberOfLongZero(NON_SYSTEM_LONG_ZERO_ADDRESS)))
                .willReturn(contractId);

        final var result = subject.runToCompletion(
                GAS_LIMIT, SENDER_ID, frame, tracer, messageCallProcessor, contractCreationProcessor);

        inOrder.verify(tracer).traceOriginAction(frame);
        inOrder.verify(contractCreationProcessor).process(frame, tracer);
        inOrder.verify(messageCallProcessor).process(childFrame, tracer);
        inOrder.verify(tracer).sanitizeTracedActions(frame);

        assertFailureExpectationsWith(frame, result);
        assertEquals(INVALID_SIGNATURE, result.haltReason());
        assertNull(result.revertReason());
    }

    @Test
    void failurePathWorksWithHaltReason() {
        final var inOrder = Mockito.inOrder(frame, childFrame, tracer, messageCallProcessor, contractCreationProcessor);

        givenBaseFailureWith(NON_SYSTEM_LONG_ZERO_ADDRESS);
        given(frame.getExceptionalHaltReason()).willReturn(Optional.of(FAILURE_DURING_LAZY_ACCOUNT_CREATION));
        final var contractId = ContractID.newBuilder()
                .contractNum(numberOfLongZero(NON_SYSTEM_LONG_ZERO_ADDRESS))
                .build();
        given(entityIdFactory.newContractId(numberOfLongZero(NON_SYSTEM_LONG_ZERO_ADDRESS)))
                .willReturn(contractId);

        final var result = subject.runToCompletion(
                GAS_LIMIT, SENDER_ID, frame, tracer, messageCallProcessor, contractCreationProcessor);

        inOrder.verify(tracer).traceOriginAction(frame);
        inOrder.verify(contractCreationProcessor).process(frame, tracer);
        inOrder.verify(messageCallProcessor).process(childFrame, tracer);
        inOrder.verify(tracer).sanitizeTracedActions(frame);

        assertFailureExpectationsWith(frame, result);
        assertEquals(FAILURE_DURING_LAZY_ACCOUNT_CREATION, result.haltReason());
        assertNull(result.revertReason());
    }

    @Test
    void failurePathWorksWithHaltReasonWhenExceedingChildRecords() {
        final var inOrder = Mockito.inOrder(frame, childFrame, tracer, messageCallProcessor, contractCreationProcessor);
        final Deque<MessageFrame> messageFrameStack = new ArrayDeque<>();
        messageFrameStack.addFirst(frame);

        givenBaseFailureWith(NON_SYSTEM_LONG_ZERO_ADDRESS);
        given(frame.getExceptionalHaltReason()).willReturn(Optional.of(INSUFFICIENT_CHILD_RECORDS));
        final var contractId = ContractID.newBuilder()
                .evmAddress(Bytes.wrap(NON_SYSTEM_LONG_ZERO_ADDRESS.toArray()))
                .build();
        given(entityIdFactory.newContractId(numberOfLongZero(NON_SYSTEM_LONG_ZERO_ADDRESS)))
                .willReturn(contractId);
        given(frame.getWorldUpdater()).willReturn(worldUpdater);

        final var result = subject.runToCompletion(
                GAS_LIMIT, SENDER_ID, frame, tracer, messageCallProcessor, contractCreationProcessor);

        inOrder.verify(tracer).traceOriginAction(frame);
        inOrder.verify(contractCreationProcessor).process(frame, tracer);
        inOrder.verify(tracer).tracePostExecution(eq(childFrame), any(Operation.OperationResult.class));
        inOrder.verify(messageCallProcessor).process(childFrame, tracer);
        inOrder.verify(tracer).sanitizeTracedActions(frame);

        assertFailureExpectationsWith(frame, result);
        assertEquals(INSUFFICIENT_CHILD_RECORDS, result.haltReason());
        assertNull(result.revertReason());
    }

    @Test
    void propagatesResourceExhaustedException() {
        final var inOrder = Mockito.inOrder(frame, childFrame, tracer, messageCallProcessor, contractCreationProcessor);

        // Setup basic scenario
        // Only set up the minimal required mocks
        final Deque<MessageFrame> messageFrameStack = new ArrayDeque<>();
        messageFrameStack.addFirst(frame);
        given(frame.getMessageFrameStack()).willReturn(messageFrameStack);
        given(frame.getType()).willReturn(Type.MESSAGE_CALL);
        given(frame.getRecipientAddress()).willReturn(NON_SYSTEM_LONG_ZERO_ADDRESS);

        final var contractId = ContractID.newBuilder()
                .evmAddress(Bytes.wrap(NON_SYSTEM_LONG_ZERO_ADDRESS.toArray()))
                .build();
        given(entityIdFactory.newContractId(numberOfLongZero(NON_SYSTEM_LONG_ZERO_ADDRESS)))
                .willReturn(contractId);
        given(frame.getWorldUpdater()).willReturn(worldUpdater);

        // Configure messageCallProcessor to throw ResourceExhaustedException
        doThrow(new ResourceExhaustedException(ResponseCodeEnum.THROTTLED_AT_CONSENSUS))
                .when(messageCallProcessor)
                .process(frame, tracer);

        // The exception should propagate through the method
        assertThrows(
                ResourceExhaustedException.class,
                () -> subject.runToCompletion(
                        GAS_LIMIT, SENDER_ID, frame, tracer, messageCallProcessor, contractCreationProcessor));

        // Verify method calls before the exception
        inOrder.verify(tracer).traceOriginAction(frame);
        inOrder.verify(messageCallProcessor).process(frame, tracer);

        // Verify sanitizeTracedActions was NOT called (since exception is propagated)
        verify(tracer, never()).sanitizeTracedActions(frame);
    }

    @Test
    void handlesGenericExceptionGracefully() {
        // Setup minimal required mocks
        final Deque<MessageFrame> messageFrameStack = new ArrayDeque<>();
        messageFrameStack.addFirst(frame);

        // Basic frame setup
        given(frame.getMessageFrameStack()).willReturn(messageFrameStack);
        given(frame.getType()).willReturn(MessageFrame.Type.MESSAGE_CALL);
        given(frame.getRecipientAddress()).willReturn(NON_SYSTEM_LONG_ZERO_ADDRESS);

        // Gas calculation setup
        given(frame.getRemainingGas()).willReturn(GAS_LIMIT / 2);
        given(gasCalculator.getMaxRefundQuotient()).willReturn(BESU_MAX_REFUND_QUOTIENT);
        given(frame.getGasRefund()).willReturn(GAS_LIMIT / 8);
        given(frame.getGasPrice()).willReturn(Wei.of(NETWORK_GAS_PRICE));

        // Context variables
        final var config = HederaTestConfigBuilder.create()
                .withValue("contracts.maxRefundPercentOfGasLimit", HEDERA_MAX_REFUND_PERCENTAGE)
                .getOrCreateConfig();
        given(frame.getContextVariable(FrameUtils.CONFIG_CONTEXT_VARIABLE)).willReturn(config);
        given(frame.getContextVariable(FrameUtils.TRACKER_CONTEXT_VARIABLE)).willReturn(null);
        given(frame.getExceptionalHaltReason()).willReturn(Optional.of(ExceptionalHaltReason.CODE_SECTION_MISSING));
        given(frame.getWorldUpdater()).willReturn(worldUpdater);

        // Contract ID setup
        final var contractId = ContractID.newBuilder()
                .contractNum(numberOfLongZero(NON_SYSTEM_LONG_ZERO_ADDRESS))
                .build();
        given(entityIdFactory.newContractId(numberOfLongZero(NON_SYSTEM_LONG_ZERO_ADDRESS)))
                .willReturn(contractId);

        // Exception behavior
        doThrow(new RuntimeException("Generic processing error"))
                .when(messageCallProcessor)
                .process(frame, tracer);

        // Run the method
        final var result = subject.runToCompletion(
                GAS_LIMIT, SENDER_ID, frame, tracer, messageCallProcessor, contractCreationProcessor);

        // Verify method calls
        verify(tracer).traceOriginAction(frame);
        verify(messageCallProcessor).process(frame, tracer);
        verify(tracer).sanitizeTracedActions(frame);

        // Verify the failure result
        assertFalse(result.isSuccess());
        assertEquals(EXPECTED_GAS_USED_NO_REFUNDS, result.gasUsed());
        assertEquals(Bytes.EMPTY, result.output());
    }

    private void assertSuccessExpectationsWith(
            @NonNull final ContractID expectedReceiverId,
            @NonNull final ContractID expectedReceiverAddress,
            @NonNull final HederaEvmTransactionResult result) {
        assertTrue(result.isSuccess());
        assertEquals(List.of(BESU_LOG), result.logs());
        assertEquals(expectedReceiverId, result.recipientId());
        assertEquals(expectedReceiverAddress, result.recipientEvmAddress());
        assertEquals(OUTPUT_DATA, result.output());
    }

    private void assertFailureExpectationsWith(
            @NonNull final MessageFrame frame, @NonNull final HederaEvmTransactionResult result) {
        assertFalse(result.isSuccess());
        assertEquals(EXPECTED_GAS_USED_NO_REFUNDS, result.gasUsed());
        assertEquals(Bytes.EMPTY, result.output());
    }

    private void givenBaseSuccessWith(@NonNull final Address receiver) {
        givenBaseScenarioWithDetails(receiver, true, false);
    }

    private void givenBaseFailureWith(@NonNull final Address receiver) {
        givenBaseScenarioWithDetails(receiver, false, false);
    }

    private void givenBaseReceiverSigCheckHaltWith(@NonNull final Address receiver) {
        givenBaseScenarioWithDetails(receiver, false, true);
    }

    private void givenBaseScenarioWithDetails(
            @NonNull final Address receiver, final boolean success, final boolean receiverSigCheckFailure) {
        final Deque<MessageFrame> messageFrameStack = new ArrayDeque<>();
        messageFrameStack.addFirst(frame);
        given(frame.getWorldUpdater()).willReturn(worldUpdater);
        given(frame.getType()).willReturn(MessageFrame.Type.CONTRACT_CREATION);
        given(childFrame.getType()).willReturn(MessageFrame.Type.MESSAGE_CALL);
        doAnswer(invocation -> {
                    messageFrameStack.pop();
                    messageFrameStack.push(childFrame);
                    if (receiverSigCheckFailure) {
                        propagatedCallFailure.set(HevmPropagatedCallFailure.MISSING_RECEIVER_SIGNATURE);
                    }
                    return null;
                })
                .when(contractCreationProcessor)
                .process(frame, tracer);
        doAnswer(invocation -> {
                    messageFrameStack.pop();
                    return null;
                })
                .when(messageCallProcessor)
                .process(childFrame, tracer);
        given(gasCalculator.getMaxRefundQuotient()).willReturn(BESU_MAX_REFUND_QUOTIENT);
        given(frame.getRemainingGas()).willReturn(GAS_LIMIT / 2);
        given(frame.getGasRefund()).willReturn(GAS_LIMIT / 8);
        final var config = HederaTestConfigBuilder.create()
                .withValue("contracts.maxRefundPercentOfGasLimit", HEDERA_MAX_REFUND_PERCENTAGE)
                .getOrCreateConfig();
        given(frame.getContextVariable(FrameUtils.CONFIG_CONTEXT_VARIABLE)).willReturn(config);
        given(frame.getContextVariable(FrameUtils.TRACKER_CONTEXT_VARIABLE)).willReturn(null);
        given(childFrame.getContextVariable(FrameUtils.PROPAGATED_CALL_FAILURE_CONTEXT_VARIABLE))
                .willReturn(propagatedCallFailure);
        given(frame.getGasPrice()).willReturn(Wei.of(NETWORK_GAS_PRICE));
        if (success) {
            given(frame.getState()).willReturn(MessageFrame.State.COMPLETED_SUCCESS);
            given(frame.getLogs()).willReturn(List.of(BESU_LOG));
            given(frame.getOutputData()).willReturn(pbjToTuweniBytes(OUTPUT_DATA));
        } else {
            given(frame.getState()).willReturn(MessageFrame.State.COMPLETED_FAILED);
        }
        given(frame.getRecipientAddress()).willReturn(receiver);
        given(frame.getMessageFrameStack()).willReturn(messageFrameStack);
        final var contractId = ContractID.newBuilder()
                .evmAddress(Bytes.wrap(receiver.toArray()))
                .build();
        given(childFrame.getMessageFrameStack()).willReturn(messageFrameStack);
    }
}
