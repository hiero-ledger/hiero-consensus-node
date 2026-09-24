// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.clpr.sendmessage;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_AUTHORIZATION_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CHANNEL_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.clpr.ClprServiceConstants.CLPR_EVM_ADDRESS_BYTES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.esaulpaugh.headlong.abi.Function;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.state.clpr.ClprConnector;
import com.hedera.hapi.node.state.clpr.ClprConnectorKey;
import com.hedera.node.app.service.clpr.ClprServiceApi;
import com.hedera.node.app.service.clpr.ReadableConnectorStore;
import com.hedera.node.app.service.contract.impl.bonneville.BonnevilleEVM;
import com.hedera.node.app.service.contract.impl.exec.ActionSidecarContentTracer;
import com.hedera.node.app.service.contract.impl.exec.AddressChecks;
import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.metrics.OpsDurationMetrics;
import com.hedera.node.app.service.contract.impl.exec.processors.CustomContractCreationProcessor;
import com.hedera.node.app.service.contract.impl.exec.processors.CustomMessageCallProcessor;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.HederaSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.sendmessage.SendMessageCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.exec.utils.OpsDurationCounter;
import com.hedera.node.app.service.contract.impl.hevm.HEVM;
import com.hedera.node.app.service.contract.impl.hevm.HederaEVM;
import com.hedera.node.app.service.contract.impl.hevm.HederaOperationsRegistry;
import com.hedera.node.app.service.contract.impl.hevm.OpsDurationSchedule;
import com.hedera.node.app.service.contract.impl.state.AbstractMutableEvmAccount;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.service.contract.impl.test.TestHelpers;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.math.BigInteger;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.Code;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.frame.BlockValues;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.PragueGasCalculator;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.operation.OperationRegistry;
import org.hyperledger.besu.evm.precompile.PrecompileContractRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SendMessageCallTest extends CallTestBase {

    private static final AccountID SENDER_ID =
            AccountID.newBuilder().accountNum(1001).build();
    private static final Address SENDER_ADDRESS = Address.fromHexString("0x1234567890abcdef1234567890abcdef12345678");
    private static final byte[] CHANNEL_ID = new byte[32];
    private static final byte[] CONNECTOR_ID = {
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30,
        31, 32
    };
    private static final byte[] TARGET_APP = new byte[] {10, 20, 30};
    private static final byte[] MESSAGE_DATA = new byte[] {1, 2, 3, 4, 5};

    private static final Bytes CONNECTOR_ID_BYTES = Bytes.wrap(CONNECTOR_ID);
    private static final ContractID CONNECTOR_CONTRACT_ID =
            ContractID.newBuilder().contractNum(9999L).build();

    // A 32-byte ABI bool(true) return value
    private static final Bytes BOOL_TRUE_RESULT;
    // A 32-byte ABI bool(false) return value
    private static final Bytes BOOL_FALSE_RESULT;

    static {
        final byte[] trueBytes = new byte[32];
        trueBytes[31] = 1;
        BOOL_TRUE_RESULT = Bytes.wrap(trueBytes);
        BOOL_FALSE_RESULT = Bytes.wrap(new byte[32]);
    }

    @Mock
    private StoreFactory storeFactory;

    @Mock
    private ClprServiceApi clprApi;

    @Mock
    private ReadableConnectorStore connectorStore;

    private ClprConnector connectorWithContract() {
        return ClprConnector.newBuilder()
                .connectorId(CONNECTOR_ID_BYTES)
                .channelId(Bytes.wrap(CHANNEL_ID))
                .connectorContract(CONNECTOR_CONTRACT_ID)
                .build();
    }

    private void givenConnectorLookup(final ClprConnector connector) {
        given(nativeOperations.storeFactory()).willReturn(storeFactory);
        given(storeFactory.readableStore(ReadableConnectorStore.class)).willReturn(connectorStore);
        final var key = new ClprConnectorKey(Bytes.wrap(CHANNEL_ID), CONNECTOR_ID_BYTES);
        given(connectorStore.getConnector(key)).willReturn(connector);
    }

    @Mock
    private ProxyWorldUpdater updater;

    @Mock
    private ProxyWorldUpdater childUpdater;

    @Mock
    private AbstractMutableEvmAccount contract;

    private void givenParentFrame(final long gas) {
        givenParentFrame(gas, Address.fromHexString("0x16e"), Code.EMPTY_CODE);
    }

    private void givenParentFrame(final long gas, final Address address, final Code code) {
        frame = MessageFrame.builder()
                .type(MessageFrame.Type.MESSAGE_CALL)
                .worldUpdater(updater)
                .initialGas(gas)
                .address(address)
                .contract(address)
                .originator(SENDER_ADDRESS)
                .sender(SENDER_ADDRESS)
                .gasPrice(Wei.ONE)
                .inputData(org.apache.tuweni.bytes.Bytes.EMPTY)
                .value(Wei.ZERO)
                .apparentValue(Wei.ZERO)
                .code(code)
                .blockValues(mock(BlockValues.class))
                .blockHashLookup((_, _) -> null)
                .miningBeneficiary(Address.ZERO)
                .contextVariables(Map.of(
                        FrameUtils.CONFIG_CONTEXT_VARIABLE,
                        TestHelpers.DEFAULT_CONFIG,
                        FrameUtils.OPS_DURATION_COUNTER,
                        OpsDurationCounter.withSchedule(
                                OpsDurationSchedule.fromConfig(TestHelpers.DEFAULT_OPS_DURATION_CONFIG))))
                .completer(_ -> {})
                .build();
    }

    private void givenAuthorizationContract() {
        given(updater.getHederaAccount(CONNECTOR_CONTRACT_ID)).willReturn(contract);
        given(contract.getAddress()).willReturn(Address.fromHexString("0xabcdef"));
        given(contract.getCode()).willReturn(org.apache.tuweni.bytes.Bytes.fromHexString("0x6000"));
        given(updater.updater()).willReturn(childUpdater);
    }

    private PricedResult executeAfterAuthorization(final Bytes output, final MessageFrame.State state) {
        givenParentFrame(200_000L);
        givenAuthorizationContract();
        final var subject = createSubject();
        final var result = new AtomicReference<PricedResult>();
        assertThat(subject.scheduleChildFrame(frame, () -> result.set(subject.execute(frame))))
                .isTrue();
        assertThat(result.get()).isNull();
        verifyNoInteractions(clprApi);
        final var child = frame.getMessageFrameStack().removeFirst();
        child.setOutputData(org.apache.tuweni.bytes.Bytes.wrap(output.toByteArray()));
        child.setState(state);
        child.notifyCompletion();
        return result.get();
    }

    @Test
    @DisplayName(
            "should return success with message ID when authorizeOutboundMessage returns true and sendMessage succeeds")
    void successReturnsMessageId() {
        givenConnectorLookup(connectorWithContract());
        given(storeFactory.serviceApi(ClprServiceApi.class)).willReturn(clprApi);
        given(clprApi.sendMessage(
                        Bytes.wrap(CHANNEL_ID),
                        CONNECTOR_ID_BYTES,
                        Bytes.wrap(TARGET_APP),
                        Bytes.wrap(SENDER_ADDRESS.getBytes().toArray()),
                        Bytes.wrap(MESSAGE_DATA)))
                .willReturn(42L);

        final var result = executeAfterAuthorization(BOOL_TRUE_RESULT, MessageFrame.State.COMPLETED_SUCCESS);

        assertThat(result.fullResult().output().toUnsignedBigInteger()).isEqualTo(java.math.BigInteger.valueOf(42L));
        assertThat(result.responseCode()).isEqualTo(SUCCESS);
        assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.COMPLETED_SUCCESS);
    }

    @Test
    @DisplayName("should revert with CLPR_AUTHORIZATION_FAILED when authorizeOutboundMessage returns false")
    void revertWhenAuthorizeFalse() {
        givenConnectorLookup(connectorWithContract());

        final var result = executeAfterAuthorization(BOOL_FALSE_RESULT, MessageFrame.State.COMPLETED_SUCCESS);

        assertThat(result.responseCode()).isEqualTo(CLPR_AUTHORIZATION_FAILED);
        assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.REVERT);
        verify(storeFactory, never()).serviceApi(ClprServiceApi.class);
    }

    @Test
    @DisplayName(
            "should revert with CLPR_AUTHORIZATION_FAILED when authorizeOutboundMessage reverts, even with true output")
    void revertWhenAuthorizeReverts() {
        givenConnectorLookup(connectorWithContract());

        final var result = executeAfterAuthorization(BOOL_TRUE_RESULT, MessageFrame.State.COMPLETED_FAILED);

        assertThat(result.responseCode()).isEqualTo(CLPR_AUTHORIZATION_FAILED);
        assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.REVERT);
        verify(storeFactory, never()).serviceApi(ClprServiceApi.class);
    }

    @Test
    @DisplayName("should revert with CLPR_AUTHORIZATION_FAILED when connector not found")
    void revertWhenConnectorNotFound() {
        givenConnectorLookup(null);

        final var subject = createSubject();
        assertThat(subject.scheduleChildFrame(frame, () -> {})).isFalse();
        final var result = subject.execute(frame);

        assertThat(result.responseCode()).isEqualTo(CLPR_AUTHORIZATION_FAILED);
        assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.REVERT);
    }

    @Test
    @DisplayName("should revert with error code when sendMessage throws HandleException")
    void revertOnHandleException() {
        givenConnectorLookup(connectorWithContract());
        given(storeFactory.serviceApi(ClprServiceApi.class)).willReturn(clprApi);
        given(clprApi.sendMessage(
                        Bytes.wrap(CHANNEL_ID),
                        CONNECTOR_ID_BYTES,
                        Bytes.wrap(TARGET_APP),
                        Bytes.wrap(SENDER_ADDRESS.getBytes().toArray()),
                        Bytes.wrap(MESSAGE_DATA)))
                .willThrow(new HandleException(CLPR_CHANNEL_NOT_FOUND));

        final var result = executeAfterAuthorization(BOOL_TRUE_RESULT, MessageFrame.State.COMPLETED_SUCCESS);

        assertThat(result.responseCode()).isEqualTo(CLPR_CHANNEL_NOT_FOUND);
        assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.REVERT);
    }

    @Test
    void suspendsOnSameStackAndReturnsUnusedGasBeforeContinuing() {
        givenConnectorLookup(connectorWithContract());
        givenParentFrame(200_000L);
        givenAuthorizationContract();
        final var continuation = mock(Runnable.class);
        final var subject = createSubject();

        assertThat(subject.scheduleChildFrame(frame, continuation)).isTrue();

        final var child = frame.getMessageFrameStack().getFirst();
        assertThat(frame.getState()).isEqualTo(MessageFrame.State.CODE_SUSPENDED);
        assertThat(frame.getMessageFrameStack()).containsExactly(child, frame);
        assertThat(child.getMessageFrameStack()).isSameAs(frame.getMessageFrameStack());
        assertThat(child.getWorldUpdater()).isSameAs(childUpdater);
        assertThat(child.isStatic()).isTrue();
        assertThat(child.getSenderAddress().getBytes().toArray()).isEqualTo(CLPR_EVM_ADDRESS_BYTES.toByteArray());
        assertThat(child.getOriginatorAddress()).isEqualTo(SENDER_ADDRESS);
        assertThat(child.getRecipientAddress()).isEqualTo(contract.getAddress());
        assertThat(child.getContractAddress()).isEqualTo(contract.getAddress());
        assertThat(child.getValue()).isEqualTo(Wei.ZERO);
        assertThat(child.getCode().getBytes()).isEqualTo(contract.getCode());
        assertThat(child.getInputData().toArray())
                .isEqualTo(SendMessageCall.encodeAuthorizeOutboundMessage(
                        CHANNEL_ID, TARGET_APP, SENDER_ADDRESS.getBytes().toArray(), MESSAGE_DATA));
        assertThat(child.getRemainingGas()).isEqualTo(50_000L);
        assertThat(frame.getRemainingGas()).isEqualTo(150_000L);
        verifyNoInteractions(continuation, clprApi);
        verify(nativeOperations, never()).dispatchReadonlyContractCall(any(), any(), any(), anyLong(), any());

        frame.getMessageFrameStack().removeFirst();
        child.decrementRemainingGas(12_345L);
        child.setState(MessageFrame.State.COMPLETED_FAILED);
        child.notifyCompletion();

        assertThat(frame.getState()).isEqualTo(MessageFrame.State.CODE_EXECUTING);
        assertThat(frame.getRemainingGas()).isEqualTo(187_655L);
        verify(continuation).run();
    }

    @ParameterizedTest
    @CsvSource({
        "false, 600160005260206000f3, true",
        "true, 600160005260206000f3, true",
        "false, 600060005260206000f3, false",
        "true, 600060005260206000f3, false",
        "false, 600160005260206000fd, false",
        "true, 600160005260206000fd, false",
        "false, 6001600055600160005260206000f3, false",
        "true, 6001600055600160005260206000f3, false"
    })
    void processesAuthorizationBytecodeBeforeEnqueuing(
            final boolean useBonneville, final String bytecode, final boolean succeeds) {
        givenConnectorLookup(connectorWithContract());
        givenParentFrame(200_000L);
        givenAuthorizationContract();
        given(contract.getCode()).willReturn(org.apache.tuweni.bytes.Bytes.fromHexString(bytecode));
        if (succeeds) {
            given(storeFactory.serviceApi(ClprServiceApi.class)).willReturn(clprApi);
        }
        final var evmGasCalculator = new PragueGasCalculator();
        final var registry = new OperationRegistry();
        HederaOperationsRegistry.forVersion(EvmSpecVersion.PRAGUE)
                .register(registry, evmGasCalculator, BigInteger.ZERO, EvmConfiguration.DEFAULT);
        final var flags = mock(FeatureFlags.class);
        final var addressChecks = mock(AddressChecks.class);
        final HEVM evm = useBonneville
                ? new BonnevilleEVM(
                        registry,
                        evmGasCalculator,
                        EvmConfiguration.DEFAULT,
                        EvmSpecVersion.PRAGUE,
                        flags,
                        addressChecks)
                : new HederaEVM(registry, evmGasCalculator, EvmConfiguration.DEFAULT, EvmSpecVersion.PRAGUE);
        final var processor = new CustomMessageCallProcessor(
                evm, flags, new PrecompileContractRegistry(), addressChecks, Map.of(), mock(ContractMetrics.class));
        final var subject = createSubject();
        final var result = new AtomicReference<PricedResult>();
        assertThat(subject.scheduleChildFrame(frame, () -> result.set(subject.execute(frame))))
                .isTrue();
        assertThat(result.get()).isNull();
        verifyNoInteractions(clprApi);
        final var child = frame.getMessageFrameStack().getFirst();

        processor.process(child, mock(ActionSidecarContentTracer.class));

        assertThat(frame.getMessageFrameStack()).containsExactly(frame);
        assertThat(result.get().responseCode()).isEqualTo(succeeds ? SUCCESS : CLPR_AUTHORIZATION_FAILED);
        if (succeeds) {
            final var order = inOrder(childUpdater, clprApi);
            order.verify(childUpdater).commit();
            order.verify(clprApi).sendMessage(any(), any(), any(), any(), any());
        } else {
            verifyNoInteractions(clprApi);
        }
    }

    @Test
    void bonnevilleResumesNestedSendMessageAfterAuthorization() {
        givenConnectorLookup(connectorWithContract());
        // CALL CLPR with a 200,000 gas stipend, then return the system contract's 32-byte message id.
        final var callerCode = new Code(
                org.apache.tuweni.bytes.Bytes.fromHexString("6020600060006000600061016e62030d40f15060206000f3"));
        givenParentFrame(400_000L, SENDER_ADDRESS, callerCode);
        final var systemUpdater = mock(ProxyWorldUpdater.class);
        final var caller = mock(AbstractMutableEvmAccount.class);
        given(updater.get(SENDER_ADDRESS)).willReturn(caller);
        given(caller.getAddress()).willReturn(SENDER_ADDRESS);
        given(caller.getCode()).willReturn(callerCode.getBytes());
        given(caller.getBalance()).willReturn(Wei.ZERO);
        given(updater.updater()).willReturn(systemUpdater);
        given(systemUpdater.getHederaAccount(CONNECTOR_CONTRACT_ID)).willReturn(contract);
        given(systemUpdater.updater()).willReturn(childUpdater);
        given(contract.getAddress()).willReturn(Address.fromHexString("0xabcdef"));
        given(contract.getCode()).willReturn(org.apache.tuweni.bytes.Bytes.fromHexString("600160005260206000f3"));
        given(storeFactory.serviceApi(ClprServiceApi.class)).willReturn(clprApi);
        given(clprApi.sendMessage(any(), any(), any(), any(), any())).willReturn(42L);
        final var evmGasCalculator = new PragueGasCalculator();
        final var registry = new OperationRegistry();
        HederaOperationsRegistry.forVersion(EvmSpecVersion.PRAGUE)
                .register(registry, evmGasCalculator, BigInteger.ZERO, EvmConfiguration.DEFAULT);
        final var flags = mock(FeatureFlags.class);
        final var addressChecks = mock(AddressChecks.class);
        final var evm = new BonnevilleEVM(
                registry, evmGasCalculator, EvmConfiguration.DEFAULT, EvmSpecVersion.PRAGUE, flags, addressChecks);
        final var systemContract = mock(HederaSystemContract.class);
        final var subject = createSubject();
        doAnswer(invocation -> {
                    final MessageFrame systemFrame = invocation.getArgument(2);
                    final Consumer<FullResult> completion = invocation.getArgument(3);
                    assertThat(subject.scheduleChildFrame(
                                    systemFrame,
                                    () -> completion.accept(
                                            subject.execute(systemFrame).fullResult())))
                            .isTrue();
                    verifyNoInteractions(clprApi);
                    return null;
                })
                .when(systemContract)
                .computeFully(any(), any(), any(), any());
        final var metrics = mock(ContractMetrics.class);
        given(metrics.opsDurationMetrics()).willReturn(mock(OpsDurationMetrics.class));
        final var processor = new CustomMessageCallProcessor(
                evm,
                flags,
                new PrecompileContractRegistry(),
                addressChecks,
                Map.of(Address.fromHexString("0x16e"), systemContract),
                metrics);
        evm.setProcessors(processor, mock(CustomContractCreationProcessor.class));

        processor.process(frame, mock(ActionSidecarContentTracer.class));

        assertThat(frame.getState()).isEqualTo(MessageFrame.State.COMPLETED_SUCCESS);
        assertThat(frame.getOutputData().toUnsignedBigInteger()).isEqualTo(BigInteger.valueOf(42L));
        assertThat(frame.getMessageFrameStack()).isEmpty();
        final var order = inOrder(childUpdater, clprApi, systemUpdater, updater);
        order.verify(childUpdater).commit();
        order.verify(clprApi).sendMessage(any(), any(), any(), any(), any());
        order.verify(systemUpdater).commit();
        order.verify(updater).commit();
    }

    @Test
    void capsChildGasAndReservesSystemContractCharge() {
        givenConnectorLookup(connectorWithContract());
        givenParentFrame(110_000L);
        givenAuthorizationContract();

        assertThat(createSubject().scheduleChildFrame(frame, () -> {})).isTrue();

        assertThat(frame.getMessageFrameStack().getFirst().getRemainingGas()).isEqualTo(9_844L);
        assertThat(frame.getRemainingGas()).isEqualTo(100_156L);
    }

    @Test
    void doesNotSpawnWhenGasCannotCoverSystemContractCharge() {
        givenConnectorLookup(connectorWithContract());
        givenParentFrame(99_999L);
        final var subject = createSubject();

        assertThat(subject.scheduleChildFrame(frame, () -> {})).isFalse();
        assertThat(frame.getMessageFrameStack()).containsExactly(frame);
        verifyNoInteractions(updater, clprApi);
    }

    @Test
    void doesNotSpawnAtDepthLimit() {
        givenConnectorLookup(connectorWithContract());
        given(frame.getDepth()).willReturn(1024);

        assertThat(createSubject().scheduleChildFrame(frame, () -> {})).isFalse();
        verifyNoInteractions(updater, clprApi);
    }

    @Test
    void rejectsMissingAuthorizationContract() {
        givenConnectorLookup(connectorWithContract());
        givenParentFrame(200_000L);
        final var subject = createSubject();

        assertThat(subject.scheduleChildFrame(frame, () -> {})).isFalse();
        assertThat(subject.execute(frame).responseCode()).isEqualTo(CLPR_AUTHORIZATION_FAILED);
        assertThat(frame.getMessageFrameStack()).containsExactly(frame);
        verifyNoInteractions(clprApi);
    }

    @Test
    void rejectsShortAuthorizationOutput() {
        givenConnectorLookup(connectorWithContract());

        final var result = executeAfterAuthorization(Bytes.wrap(new byte[4]), MessageFrame.State.COMPLETED_SUCCESS);

        assertThat(result.responseCode()).isEqualTo(CLPR_AUTHORIZATION_FAILED);
        verifyNoInteractions(clprApi);
    }

    @Test
    @DisplayName("should not allow static frame")
    void doesNotAllowStaticFrame() {
        assertThat(createSubject().allowsStaticFrame()).isFalse();
    }

    @Test
    @DisplayName("encodeAuthorizeOutboundMessage matches ABI encoding")
    void encodeAuthorizeOutboundMessageMatchesAbi() {
        final var function = new Function("authorizeOutboundMessage(bytes32,bytes,bytes,bytes)", "(bool)");
        final var expected = function.encodeCall(Tuple.of(
                        CHANNEL_ID, TARGET_APP, SENDER_ADDRESS.getBytes().toArray(), MESSAGE_DATA))
                .array();

        assertThat(SendMessageCall.encodeAuthorizeOutboundMessage(
                        CHANNEL_ID, TARGET_APP, SENDER_ADDRESS.getBytes().toArray(), MESSAGE_DATA))
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("decodeBoolResult returns true for a true ABI word")
    void decodeBoolResultTrue() {
        assertThat(SendMessageCall.decodeBoolResult(BOOL_TRUE_RESULT)).isTrue();
    }

    @Test
    @DisplayName("decodeBoolResult returns false for a false ABI word")
    void decodeBoolResultFalse() {
        assertThat(SendMessageCall.decodeBoolResult(BOOL_FALSE_RESULT)).isFalse();
    }

    @Test
    @DisplayName("decodeBoolResult returns false for output shorter than 32 bytes")
    void decodeBoolResultShort() {
        assertThat(SendMessageCall.decodeBoolResult(Bytes.wrap(new byte[4]))).isFalse();
    }

    private SendMessageCall createSubject() {
        return new SendMessageCall(
                mockEnhancement(),
                gasCalculator,
                SENDER_ID,
                SENDER_ADDRESS,
                CHANNEL_ID,
                CONNECTOR_ID,
                TARGET_APP,
                MESSAGE_DATA);
    }
}
