// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.processors;

import static com.hedera.hapi.streams.ContractActionType.PRECOMPILE;
import static com.hedera.hapi.streams.ContractActionType.SYSTEM;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.PrngSystemContract.PRNG_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.CONFIG_CONTEXT_VARIABLE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.REMAINING_GAS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.isSameResult;
import static org.hyperledger.besu.evm.frame.ExceptionalHaltReason.INSUFFICIENT_GAS;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.node.app.service.contract.impl.exec.ActionSidecarContentTracer;
import com.hedera.node.app.service.contract.impl.exec.AddressChecks;
import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason;
import com.hedera.node.app.service.contract.impl.exec.processors.CustomMessageCallProcessor;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.PrngSystemContract;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.service.contract.impl.test.TestHelpers;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.precompile.PrecompileContractRegistry;
import org.hyperledger.besu.evm.precompile.PrecompiledContract;
import org.hyperledger.besu.evm.precompile.PrecompiledContract.PrecompileContractResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CustomMessageCallProcessorTest {
    private static final long ZERO_GAS_REQUIREMENT = 0L;
    private static final long GAS_REQUIREMENT = 2L;
    private static final Bytes INPUT_DATA = Bytes.fromHexString("0x1234");
    private static final Bytes OUTPUT_DATA = Bytes.fromHexString("0x5678");
    private static final Bytes NOOP_OUTPUT_DATA = Bytes.fromHexString("0x");
    private static final Address NON_EVM_PRECOMPILE_SYSTEM_ADDRESS = Address.fromHexString("0x222");
    private static final Address CODE_ADDRESS = Address.fromHexString("0x111222333");
    private static final Address SENDER_ADDRESS = Address.fromHexString("0x222333444");
    private static final Address RECEIVER_ADDRESS = Address.fromHexString("0x33344455");
    private static final Address ADDRESS_6 = Address.fromHexString("0x6");

    @Mock
    private EVM evm;

    @Mock
    private MessageFrame frame;

    @Mock
    private FeatureFlags featureFlags;

    @Mock
    private AddressChecks addressChecks;

    @Mock
    private PrngSystemContract prngPrecompile;

    @Mock
    private PrecompiledContract nativePrecompile;

    @Mock
    private ActionSidecarContentTracer operationTracer;

    @Mock
    private PrecompileContractRegistry registry;

    @Mock
    private Configuration config;

    @Mock
    private ProxyWorldUpdater proxyWorldUpdater;

    @Mock
    private PrecompileContractResult result;

    private CustomMessageCallProcessor subject;

    @BeforeEach
    void setUp() {
        subject = new CustomMessageCallProcessor(
                evm,
                featureFlags,
                registry,
                addressChecks,
                Map.of(TestHelpers.PRNG_SYSTEM_CONTRACT_ADDRESS, prngPrecompile));
    }

    @Test
    void delegatesLazyCreationCheck() {
        given(featureFlags.isImplicitCreationEnabled(config)).willReturn(true);
        assertTrue(subject.isImplicitCreationEnabled(config));
    }

    @Test
    void callPrngSystemContractHappyPath() {
        givenPrngCall(ZERO_GAS_REQUIREMENT);
        given(frame.getValue()).willReturn(Wei.ZERO);
        given(result.getOutput()).willReturn(OUTPUT_DATA);
        given(result.getState()).willReturn(MessageFrame.State.CODE_SUCCESS);

        subject.start(frame, operationTracer);

        verify(prngPrecompile).computeFully(PRNG_CONTRACT_ID, TestHelpers.PRNG_SYSTEM_CONTRACT_ADDRESS, frame);
        verify(result).isRefundGas();
        verify(frame).decrementRemainingGas(ZERO_GAS_REQUIREMENT);
        verify(frame).setOutputData(OUTPUT_DATA);
        verify(frame).setState(MessageFrame.State.CODE_SUCCESS);
        verify(frame).setExceptionalHaltReason(Optional.empty());
        verify(operationTracer).tracePrecompileResult(frame, SYSTEM);
    }

    @Test
    void callPrngSystemContractInsufficientGas() {
        givenPrngCall(GAS_REQUIREMENT);
        given(frame.getValue()).willReturn(Wei.ZERO);

        subject.start(frame, operationTracer);

        verify(prngPrecompile).computeFully(PRNG_CONTRACT_ID, TestHelpers.PRNG_SYSTEM_CONTRACT_ADDRESS, frame);
        verifyHalt(INSUFFICIENT_GAS, false);
        verify(operationTracer).tracePrecompileResult(frame, SYSTEM);
    }

    @Test
    void callsToNonStandardSystemContractsAreNotSupported() {
        final Deque<MessageFrame> stack = new ArrayDeque<>();
        givenCallWithCode(NON_EVM_PRECOMPILE_SYSTEM_ADDRESS);

        given(addressChecks.isSystemAccount(NON_EVM_PRECOMPILE_SYSTEM_ADDRESS)).willReturn(true);
        when(frame.getValue()).thenReturn(Wei.ZERO);

        subject.start(frame, operationTracer);
        verify(frame).setOutputData(NOOP_OUTPUT_DATA);
        verify(frame).setState(MessageFrame.State.COMPLETED_SUCCESS);
        verify(frame).setExceptionalHaltReason(Optional.empty());
    }

    @Test
    void valueCannotBeTransferredToSystemContracts() {
        final Deque<MessageFrame> stack = new ArrayDeque<>();
        final var isHalted = new AtomicBoolean();
        givenHaltableFrame(isHalted);
        givenCallWithCode(ADDRESS_6);
        given(addressChecks.isSystemAccount(ADDRESS_6)).willReturn(true);
        given(frame.getValue()).willReturn(Wei.ONE);

        subject.start(frame, operationTracer);

        verifyHalt(CustomExceptionalHaltReason.INVALID_CONTRACT_ID);
    }

    @Test
    void haltsIfValueTransferFails() {
        final var isHalted = new AtomicBoolean();
        givenWellKnownUserSpaceCall();
        givenHaltableFrame(isHalted);
        given(frame.getValue()).willReturn(Wei.ONE);
        given(frame.getWorldUpdater()).willReturn(proxyWorldUpdater);
        givenExecutingFrame();
        given(addressChecks.isPresent(RECEIVER_ADDRESS, frame)).willReturn(true);
        given(proxyWorldUpdater.tryTransfer(SENDER_ADDRESS, RECEIVER_ADDRESS, Wei.ONE.toLong(), true))
                .willReturn(Optional.of(ExceptionalHaltReason.ILLEGAL_STATE_CHANGE));

        subject.start(frame, operationTracer);

        verifyHalt(ExceptionalHaltReason.ILLEGAL_STATE_CHANGE);
    }

    @Test
    void haltsAndTracesInsufficientGasIfPrecompileGasRequirementExceedsRemaining() {
        givenEvmPrecompileCall();
        given(nativePrecompile.gasRequirement(INPUT_DATA)).willReturn(GAS_REQUIREMENT);
        given(frame.getRemainingGas()).willReturn(1L);

        subject.start(frame, operationTracer);

        verifyHalt(INSUFFICIENT_GAS, false);
        verify(operationTracer).tracePrecompileResult(frame, PRECOMPILE);
    }

    @Test
    void updatesFrameBySuccessfulPrecompileResultWithGasRefund() {
        givenEvmPrecompileCall();
        final var result = new PrecompiledContract.PrecompileContractResult(
                OUTPUT_DATA, true, MessageFrame.State.CODE_SUCCESS, Optional.empty());
        given(nativePrecompile.computePrecompile(INPUT_DATA, frame)).willReturn(result);
        given(nativePrecompile.gasRequirement(INPUT_DATA)).willReturn(GAS_REQUIREMENT);
        given(frame.getRemainingGas()).willReturn(3L);

        subject.start(frame, operationTracer);

        verify(frame).decrementRemainingGas(GAS_REQUIREMENT);
        verify(frame).incrementRemainingGas(GAS_REQUIREMENT);
        verify(frame).setOutputData(OUTPUT_DATA);
        verify(frame).setState(MessageFrame.State.CODE_SUCCESS);
        verify(frame).setExceptionalHaltReason(Optional.empty());
    }

    @Test
    void revertsFrameFromPrecompileResult() {
        givenEvmPrecompileCall();
        final var result = new PrecompiledContract.PrecompileContractResult(
                OUTPUT_DATA, false, MessageFrame.State.REVERT, Optional.empty());
        given(nativePrecompile.computePrecompile(INPUT_DATA, frame)).willReturn(result);
        given(nativePrecompile.gasRequirement(INPUT_DATA)).willReturn(GAS_REQUIREMENT);
        given(frame.getRemainingGas()).willReturn(3L);

        subject.start(frame, operationTracer);

        verify(frame).decrementRemainingGas(GAS_REQUIREMENT);
        verify(frame).setRevertReason(OUTPUT_DATA);
        verify(frame, never()).setOutputData(OUTPUT_DATA);
        verify(frame).setState(MessageFrame.State.REVERT);
        verify(frame).setExceptionalHaltReason(Optional.empty());
    }

    @Test
    void triesLazyCreationBeforeValueTransferIfRecipientMissing() {
        givenWellKnownUserSpaceCall();
        given(frame.getValue()).willReturn(Wei.ONE);
        given(frame.getWorldUpdater()).willReturn(proxyWorldUpdater);
        given(proxyWorldUpdater.tryLazyCreation(RECEIVER_ADDRESS, frame)).willReturn(Optional.empty());
        given(proxyWorldUpdater.tryTransfer(SENDER_ADDRESS, RECEIVER_ADDRESS, Wei.ONE.toLong(), true))
                .willReturn(Optional.empty());
        givenExecutingFrame();

        subject.start(frame, operationTracer);

        verify(frame).setState(MessageFrame.State.CODE_EXECUTING);
    }

    @Test
    void tracesFailedCreateResultAfterHaltedLazyCreation() {
        givenWellKnownUserSpaceCall();
        given(frame.getRemainingGas()).willReturn(REMAINING_GAS);
        given(frame.getValue()).willReturn(Wei.ONE);
        given(frame.getWorldUpdater()).willReturn(proxyWorldUpdater);
        given(proxyWorldUpdater.tryLazyCreation(RECEIVER_ADDRESS, frame)).willReturn(Optional.of(INSUFFICIENT_GAS));
        givenExecutingFrame();

        subject.start(frame, operationTracer);

        verify(frame).decrementRemainingGas(REMAINING_GAS);
        verify(frame).setState(MessageFrame.State.EXCEPTIONAL_HALT);
        verify(operationTracer).traceAccountCreationResult(frame, Optional.of(INSUFFICIENT_GAS));
    }

    @Test
    void callsToDisabledPrecompile() {
        givenDisabledEvmPrecompileCall();

        subject.start(frame, operationTracer);

        verify(frame).setOutputData(NOOP_OUTPUT_DATA);
        verify(frame).setState(MessageFrame.State.COMPLETED_SUCCESS);
        verify(frame).setExceptionalHaltReason(Optional.empty());
    }

    private void givenHaltableFrame(@NonNull final AtomicBoolean isHalted) {
        doAnswer(invocation -> {
                    isHalted.set(true);
                    return null;
                })
                .when(frame)
                .setExceptionalHaltReason(any());
        doAnswer(invocation -> isHalted.get() ? MessageFrame.State.EXCEPTIONAL_HALT : MessageFrame.State.NOT_STARTED)
                .when(frame)
                .getState();
    }

    private void givenCallWithIsTopLevelTransaction(@NonNull final AtomicBoolean isTopLevelTransaction) {
        doAnswer(invocation -> {
                    isTopLevelTransaction.set(false);
                    return null;
                })
                .when(frame)
                .setExceptionalHaltReason(any());
    }

    private void givenCallWithCode(@NonNull final Address contract) {
        given(frame.getContractAddress()).willReturn(contract);
    }

    private void givenWellKnownUserSpaceCall() {
        given(frame.getContractAddress()).willReturn(CODE_ADDRESS);
        given(frame.getRecipientAddress()).willReturn(RECEIVER_ADDRESS);
        given(frame.getSenderAddress()).willReturn(SENDER_ADDRESS);
    }

    private void givenEvmPrecompileCall() {
        final Deque<MessageFrame> stack = new ArrayDeque<>();
        given(registry.get(ADDRESS_6)).willReturn(nativePrecompile);
        given(frame.getContractAddress()).willReturn(ADDRESS_6);
        given(frame.getInputData()).willReturn(INPUT_DATA);
        given(frame.getMessageFrameStack()).willReturn(stack);
        given(frame.getContextVariable(CONFIG_CONTEXT_VARIABLE)).willReturn(DEFAULT_CONFIG);
    }

    private void givenDisabledEvmPrecompileCall() {
        final Deque<MessageFrame> stack = new ArrayDeque<>();
        Configuration DISABLED_PRECOMPILE_CONFIG = HederaTestConfigBuilder.create()
                .withValue("contracts.precompile.disabled", "6")
                .getOrCreateConfig();
        given(registry.get(ADDRESS_6)).willReturn(nativePrecompile);
        given(frame.getContractAddress()).willReturn(ADDRESS_6);
        given(frame.getMessageFrameStack()).willReturn(stack);
        given(frame.getContextVariable(CONFIG_CONTEXT_VARIABLE)).willReturn(DISABLED_PRECOMPILE_CONFIG);
        when(frame.getValue()).thenReturn(Wei.ZERO);
        given(addressChecks.isSystemAccount(ADDRESS_6)).willReturn(true);
    }

    private void givenPrngCall(long gasRequirement) {
        givenCallWithCode(TestHelpers.PRNG_SYSTEM_CONTRACT_ADDRESS);
        given(frame.getInputData()).willReturn(TestHelpers.PRNG_SYSTEM_CONTRACT_ADDRESS);
        given(prngPrecompile.computeFully(any(), any(), any()))
                .willReturn(new FullResult(result, gasRequirement, null));
    }

    private void verifyHalt(@NonNull final ExceptionalHaltReason reason) {
        verifyHalt(reason, true);
    }

    private void verifyHalt(@NonNull final ExceptionalHaltReason reason, final boolean alsoVerifyTrace) {
        verify(frame).setExceptionalHaltReason(Optional.of(reason));
        verify(frame).setState(MessageFrame.State.EXCEPTIONAL_HALT);
        verify(frame, never()).setState(MessageFrame.State.CODE_EXECUTING);
        if (alsoVerifyTrace) {
            verify(operationTracer)
                    .tracePostExecution(
                            eq(frame),
                            argThat(result -> isSameResult(new Operation.OperationResult(0, reason), result)));
        }
    }

    private void givenExecutingFrame() {
        final Deque<MessageFrame> stack = new ArrayDeque<>();
        stack.push(frame);
        stack.push(frame);
        given(frame.getMessageFrameStack()).willReturn(stack);
    }
}
