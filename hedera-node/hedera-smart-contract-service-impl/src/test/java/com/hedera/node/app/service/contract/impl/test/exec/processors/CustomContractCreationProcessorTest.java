// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.processors;

import static com.hedera.node.app.service.contract.impl.exec.processors.ProcessorModule.INITIAL_CONTRACT_NONCE;
import static com.hedera.node.app.service.contract.impl.exec.processors.ProcessorModule.REQUIRE_CODE_DEPOSIT_TO_SUCCEED;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.BYTECODE_SIDECARS_VARIABLE;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.CONFIG_CONTEXT_VARIABLE;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.PENDING_CREATION_BUILDER_CONTEXT_VARIABLE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.block.stream.trace.ExecutedInitcode;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason;
import com.hedera.node.app.service.contract.impl.exec.processors.CustomContractCreationProcessor;
import com.hedera.node.app.service.contract.impl.exec.utils.PendingCreationMetadata;
import com.hedera.node.app.service.contract.impl.exec.utils.PendingCreationMetadataRef;
import com.hedera.node.app.service.contract.impl.hevm.HEVM;
import com.hedera.node.app.service.contract.impl.records.ContractOperationStreamBuilder;
import com.hedera.node.app.service.contract.impl.state.ProxyEvmContract;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.spi.workflows.ResourceExhaustedException;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.Code;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.contractvalidation.MaxCodeSizeRule;
import org.hyperledger.besu.evm.contractvalidation.PrefixCodeRule;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.tracing.OperationTracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CustomContractCreationProcessorTest {

    @Mock
    private HEVM evm;

    @Mock
    private EvmConfiguration evmConfiguration;

    @Mock
    private GasCalculator gasCalculator;

    @Mock
    private ProxyEvmContract contract;

    @Mock
    private MessageFrame frame;

    @Mock
    private OperationTracer tracer;

    @Mock
    private ProxyWorldUpdater worldUpdater;

    @Mock
    private ContractOperationStreamBuilder streamBuilder;

    @Mock
    private Code code;

    private CustomContractCreationProcessor subject;

    @BeforeEach
    void setUp() {
        var standardRules =
                List.of(MaxCodeSizeRule.from(EvmSpecVersion.defaultVersion(), evmConfiguration), PrefixCodeRule.of());
        subject = new CustomContractCreationProcessor(
                evm, REQUIRE_CODE_DEPOSIT_TO_SUCCEED, standardRules, INITIAL_CONTRACT_NONCE);
    }

    @Test
    void createsExpectedContractIfDidNotAlreadyExist() {
        given(frame.getSenderAddress()).willReturn(NON_SYSTEM_LONG_ZERO_ADDRESS);
        given(frame.getContractAddress()).willReturn(EIP_1014_ADDRESS);
        given(frame.getWorldUpdater()).willReturn(worldUpdater);
        given(contract.getCode()).willReturn(Bytes.EMPTY);
        given(contract.isStorageEmpty()).willReturn(true);
        given(worldUpdater.getOrCreate(EIP_1014_ADDRESS)).willReturn(contract);
        given(frame.getValue()).willReturn(WEI_VALUE);

        subject.start(frame, tracer);

        verify(worldUpdater).tryTransfer(NON_SYSTEM_LONG_ZERO_ADDRESS, EIP_1014_ADDRESS, WEI_VALUE.toLong(), false);
        verify(contract).setNonce(INITIAL_CONTRACT_NONCE);
        verify(frame).setState(MessageFrame.State.CODE_EXECUTING);
    }

    @Test
    void haltsOnFailedCreationDueToEntityLimit() {
        given(frame.getContractAddress()).willReturn(EIP_1014_ADDRESS);
        given(frame.getWorldUpdater()).willReturn(worldUpdater);
        given(worldUpdater.getOrCreate(EIP_1014_ADDRESS))
                .willThrow(new ResourceExhaustedException(
                        ResponseCodeEnum.MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED));
        final Optional<ExceptionalHaltReason> expectedHaltReason =
                Optional.of(CustomExceptionalHaltReason.CONTRACT_ENTITY_LIMIT_REACHED);

        subject.start(frame, tracer);

        verify(frame).setExceptionalHaltReason(expectedHaltReason);
        verify(frame).setState(MessageFrame.State.EXCEPTIONAL_HALT);
        verify(tracer).traceAccountCreationResult(frame, expectedHaltReason);
    }

    @Test
    void haltsOnFailedCreationDueToChildRecordLimit() {
        given(frame.getContractAddress()).willReturn(EIP_1014_ADDRESS);
        given(frame.getWorldUpdater()).willReturn(worldUpdater);
        given(worldUpdater.getOrCreate(EIP_1014_ADDRESS))
                .willThrow(new ResourceExhaustedException(ResponseCodeEnum.MAX_CHILD_RECORDS_EXCEEDED));
        final Optional<ExceptionalHaltReason> expectedHaltReason =
                Optional.of(CustomExceptionalHaltReason.INSUFFICIENT_CHILD_RECORDS);

        subject.start(frame, tracer);

        verify(frame).setExceptionalHaltReason(expectedHaltReason);
        verify(frame).setState(MessageFrame.State.EXCEPTIONAL_HALT);
        verify(tracer).traceAccountCreationResult(frame, expectedHaltReason);
    }

    @Test
    void propagatesIseOnUnrecognizedCreationFailure() {
        given(frame.getContractAddress()).willReturn(EIP_1014_ADDRESS);
        given(frame.getWorldUpdater()).willReturn(worldUpdater);
        given(worldUpdater.getOrCreate(EIP_1014_ADDRESS))
                .willThrow(new ResourceExhaustedException(ResponseCodeEnum.INVALID_ALIAS_KEY));

        assertThrows(IllegalStateException.class, () -> subject.start(frame, tracer));
    }

    @Test
    void haltsOnFailedTransfer() {
        given(frame.getSenderAddress()).willReturn(NON_SYSTEM_LONG_ZERO_ADDRESS);
        given(frame.getContractAddress()).willReturn(EIP_1014_ADDRESS);
        given(frame.getWorldUpdater()).willReturn(worldUpdater);
        given(contract.getCode()).willReturn(Bytes.EMPTY);
        given(contract.isStorageEmpty()).willReturn(true);
        given(worldUpdater.getOrCreate(EIP_1014_ADDRESS)).willReturn(contract);
        given(frame.getValue()).willReturn(WEI_VALUE);
        final var maybeReasonToHalt = Optional.of(ExceptionalHaltReason.ILLEGAL_STATE_CHANGE);
        given(worldUpdater.tryTransfer(NON_SYSTEM_LONG_ZERO_ADDRESS, EIP_1014_ADDRESS, WEI_VALUE.toLong(), false))
                .willReturn(maybeReasonToHalt);

        subject.start(frame, tracer);

        verify(frame).setExceptionalHaltReason(maybeReasonToHalt);
        verify(frame).setState(MessageFrame.State.EXCEPTIONAL_HALT);
        verify(tracer).traceAccountCreationResult(frame, maybeReasonToHalt);
    }

    @Test
    void haltsWithInsufficientGasIfContractExistsWithNonce() {
        given(frame.getContractAddress()).willReturn(EIP_1014_ADDRESS);
        given(frame.getWorldUpdater()).willReturn(worldUpdater);
        given(contract.getNonce()).willReturn(1L);
        given(worldUpdater.getOrCreate(EIP_1014_ADDRESS)).willReturn(contract);

        subject.start(frame, tracer);

        verify(worldUpdater, never())
                .tryTransfer(NON_SYSTEM_LONG_ZERO_ADDRESS, EIP_1014_ADDRESS, WEI_VALUE.toLong(), false);
        verify(contract, never()).setNonce(INITIAL_CONTRACT_NONCE);
        final var maybeReasonToHalt = Optional.of(ExceptionalHaltReason.INSUFFICIENT_GAS);
        verify(frame).setExceptionalHaltReason(maybeReasonToHalt);
        verify(frame).setState(MessageFrame.State.EXCEPTIONAL_HALT);
        verify(tracer).traceAccountCreationResult(frame, maybeReasonToHalt);
    }

    @Test
    void haltsWithInsufficientGasIfContractExistsWithNonEmptyCode() {
        given(frame.getContractAddress()).willReturn(EIP_1014_ADDRESS);
        given(frame.getWorldUpdater()).willReturn(worldUpdater);
        given(contract.getCode()).willReturn(CONTRACT_CODE.getBytes());
        given(worldUpdater.getOrCreate(EIP_1014_ADDRESS)).willReturn(contract);

        subject.start(frame, tracer);

        verify(worldUpdater, never())
                .tryTransfer(NON_SYSTEM_LONG_ZERO_ADDRESS, EIP_1014_ADDRESS, WEI_VALUE.toLong(), false);
        verify(contract, never()).setNonce(INITIAL_CONTRACT_NONCE);
        final var maybeReasonToHalt = Optional.of(ExceptionalHaltReason.INSUFFICIENT_GAS);
        verify(frame).setExceptionalHaltReason(maybeReasonToHalt);
        verify(frame).setState(MessageFrame.State.EXCEPTIONAL_HALT);
        verify(tracer).traceAccountCreationResult(frame, maybeReasonToHalt);
    }

    @Test
    void haltsWithInsufficientGasIfContractExistsWithNonEmptyStorage() {
        given(frame.getContractAddress()).willReturn(EIP_1014_ADDRESS);
        given(frame.getWorldUpdater()).willReturn(worldUpdater);
        given(contract.getCode()).willReturn(Bytes.EMPTY);
        given(contract.isStorageEmpty()).willReturn(false);
        given(worldUpdater.getOrCreate(EIP_1014_ADDRESS)).willReturn(contract);

        subject.start(frame, tracer);

        verify(worldUpdater, never())
                .tryTransfer(NON_SYSTEM_LONG_ZERO_ADDRESS, EIP_1014_ADDRESS, WEI_VALUE.toLong(), false);
        verify(contract, never()).setNonce(INITIAL_CONTRACT_NONCE);
        final var maybeReasonToHalt = Optional.of(ExceptionalHaltReason.INSUFFICIENT_GAS);
        verify(frame).setExceptionalHaltReason(maybeReasonToHalt);
        verify(frame).setState(MessageFrame.State.EXCEPTIONAL_HALT);
        verify(tracer).traceAccountCreationResult(frame, maybeReasonToHalt);
    }

    @Test
    void codeSuccessWithValidationFailedAddsInitcodeSidecarWhenInitcodePresent() {
        final var contractId = ContractID.newBuilder().contractNum(123L).build();
        setupCodeSuccessFrame(contractId, DEFAULT_CONFIG);
        given(frame.getState()).willReturn(MessageFrame.State.EXCEPTIONAL_HALT);

        subject.codeSuccess(frame, tracer);

        verify(streamBuilder).addContractBytecode(any(), any(Boolean.class));
    }

    @Test
    void codeSuccessElseBranchAddsRuntimeBytecodeWithInitcodeWhenInitcodePresent() {
        final var contractId = ContractID.newBuilder().contractNum(456L).build();
        setupCodeSuccessFrame(contractId, DEFAULT_CONFIG);

        subject.codeSuccess(frame, tracer);

        verify(streamBuilder).addContractBytecode(any(), any(Boolean.class));
        verify(streamBuilder).addInitcode(any());
    }

    @Test
    void codeSuccessSkipsBytecodeSidecarOnValidationSuccessWhenStreamModeIsBlocks() {
        final var contractId = ContractID.newBuilder().contractNum(987L).build();
        final var blocksConfig = HederaTestConfigBuilder.create()
                .withValue("blockStream.streamMode", "BLOCKS")
                .getOrCreateConfig();

        setupCodeSuccessFrame(contractId, blocksConfig);

        subject.codeSuccess(frame, tracer);

        verify(streamBuilder, never()).addContractBytecode(any(), any(Boolean.class));
        verify(streamBuilder).addInitcode(any());
    }

    @Test
    void codeSuccessSplitsInitcodeAtRuntimeBytecodeOffset() {
        final var contractId = ContractID.newBuilder().contractNum(321L).build();
        // initcode = [9, 1, 2, 3, 9], runtime bytecode = [1, 2, 3] -> found at offset 1
        setupCodeSuccessFrame(contractId, DEFAULT_CONFIG, Bytes.of(1, 2, 3), Bytes.of(9, 1, 2, 3, 9));

        subject.codeSuccess(frame, tracer);

        final var captor = ArgumentCaptor.forClass(ExecutedInitcode.class);
        verify(streamBuilder).addInitcode(captor.capture());
        final var executed = captor.getValue();
        assertNull(executed.explicitInitcode());
        assertNotNull(executed.initcodeBookends());
        assertEquals(
                com.hedera.pbj.runtime.io.buffer.Bytes.wrap(new byte[] {9}),
                executed.initcodeBookends().deployBytecode());
        assertEquals(
                com.hedera.pbj.runtime.io.buffer.Bytes.wrap(new byte[] {9}),
                executed.initcodeBookends().metadataBytecode());
    }

    @Test
    void codeSuccessSplitsInitcodeWhenRuntimeBytecodeAtHead() {
        final var contractId = ContractID.newBuilder().contractNum(322L).build();
        // initcode = [1, 2, 3, 9], runtime bytecode = [1, 2, 3] -> found at offset 0 (empty left bookend)
        setupCodeSuccessFrame(contractId, DEFAULT_CONFIG, Bytes.of(1, 2, 3), Bytes.of(1, 2, 3, 9));

        subject.codeSuccess(frame, tracer);

        final var captor = ArgumentCaptor.forClass(ExecutedInitcode.class);
        verify(streamBuilder).addInitcode(captor.capture());
        final var executed = captor.getValue();
        assertNotNull(executed.initcodeBookends());
        assertEquals(
                com.hedera.pbj.runtime.io.buffer.Bytes.EMPTY,
                executed.initcodeBookends().deployBytecode());
        assertEquals(
                com.hedera.pbj.runtime.io.buffer.Bytes.wrap(new byte[] {9}),
                executed.initcodeBookends().metadataBytecode());
    }

    @Test
    void codeSuccessSplitsInitcodeWhenRuntimeBytecodeAtTail() {
        final var contractId = ContractID.newBuilder().contractNum(323L).build();
        // initcode = [9, 1, 2, 3], runtime bytecode = [1, 2, 3] -> found at offset 1 (empty right bookend)
        setupCodeSuccessFrame(contractId, DEFAULT_CONFIG, Bytes.of(1, 2, 3), Bytes.of(9, 1, 2, 3));

        subject.codeSuccess(frame, tracer);

        final var captor = ArgumentCaptor.forClass(ExecutedInitcode.class);
        verify(streamBuilder).addInitcode(captor.capture());
        final var executed = captor.getValue();
        assertNotNull(executed.initcodeBookends());
        assertEquals(
                com.hedera.pbj.runtime.io.buffer.Bytes.wrap(new byte[] {9}),
                executed.initcodeBookends().deployBytecode());
        assertEquals(
                com.hedera.pbj.runtime.io.buffer.Bytes.EMPTY,
                executed.initcodeBookends().metadataBytecode());
    }

    @Test
    void codeSuccessUsesExplicitInitcodeWhenRuntimeBytecodeAbsent() {
        final var contractId = ContractID.newBuilder().contractNum(324L).build();
        // initcode = [1, 2, 3], runtime bytecode = [7, 8] -> not present
        setupCodeSuccessFrame(contractId, DEFAULT_CONFIG, Bytes.of(7, 8), Bytes.of(1, 2, 3));

        subject.codeSuccess(frame, tracer);

        final var captor = ArgumentCaptor.forClass(ExecutedInitcode.class);
        verify(streamBuilder).addInitcode(captor.capture());
        final var executed = captor.getValue();
        assertNull(executed.initcodeBookends());
        assertEquals(
                com.hedera.pbj.runtime.io.buffer.Bytes.wrap(new byte[] {1, 2, 3}), executed.explicitInitcode());
    }

    private void setupCodeSuccessFrame(final ContractID contractId, final Configuration config) {
        setupCodeSuccessFrame(contractId, config, Bytes.EMPTY, Bytes.of(1, 2, 3));
    }

    private void setupCodeSuccessFrame(
            final ContractID contractId,
            final Configuration config,
            final Bytes runtimeBytecode,
            final Bytes initcode) {

        given(frame.getMessageFrameStack()).willReturn(new ArrayDeque<>());
        given(frame.hasContextVariable(BYTECODE_SIDECARS_VARIABLE)).willReturn(true);
        given(frame.getContextVariable(CONFIG_CONTEXT_VARIABLE)).willReturn(config);

        final var metadataRef = new PendingCreationMetadataRef();
        metadataRef.set(contractId, new PendingCreationMetadata(streamBuilder, true));
        given(frame.getContextVariable(PENDING_CREATION_BUILDER_CONTEXT_VARIABLE))
                .willReturn(metadataRef);

        // super.codeSuccess(): empty output passes both validation rules; deposit cost = 0
        given(frame.getOutputData()).willReturn(Bytes.EMPTY);
        given(evm.getGasCalculator()).willReturn(gasCalculator);
        given(gasCalculator.codeDepositGasCost(0)).willReturn(0L);
        given(frame.getRemainingGas()).willReturn(100L);
        given(frame.getWorldUpdater()).willReturn(worldUpdater);
        given(worldUpdater.getOrCreate(any())).willReturn(contract);

        // CustomContractCreationProcessor.codeSuccess() reads recipient and its code
        given(frame.getRecipientAddress()).willReturn(EIP_1014_ADDRESS);
        given(worldUpdater.getHederaAccount(EIP_1014_ADDRESS)).willReturn(contract);
        given(contract.hederaContractId()).willReturn(contractId);
        given(contract.getCode()).willReturn(runtimeBytecode);

        given(frame.getCode()).willReturn(code);
        given(code.getBytes()).willReturn(initcode);
    }
}
