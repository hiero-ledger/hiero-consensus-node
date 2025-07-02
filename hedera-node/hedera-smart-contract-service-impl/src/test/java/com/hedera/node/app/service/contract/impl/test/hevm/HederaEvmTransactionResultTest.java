// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.hevm;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_EXECUTION_EXCEPTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OBTAINER_SAME_CONTRACT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.WRONG_NONCE;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.SELF_DESTRUCT_TO_SELF;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.TRACKER_CONTEXT_VARIABLE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.BESU_LOGS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALLED_CONTRACT_EVM_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALLED_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CHILD_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.ETH_DATA_WITH_TO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.GAS_LIMIT;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NONCES;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OUTPUT_DATA;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SOME_REVERT_REASON;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SOME_STORAGE_ACCESSES;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.TWO_STORAGE_ACCESSES;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.WEI_NETWORK_GAS_PRICE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.givenConfigInFrame;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.givenDefaultConfigInFrame;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.bloomForAll;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.pbjLogsFrom;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.pbjToTuweniBytes;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.node.app.service.contract.impl.exec.ActionSidecarContentTracer;
import com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransactionResult;
import com.hedera.node.app.service.contract.impl.infra.StorageAccessTracker;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.service.contract.impl.state.RootProxyWorldUpdater;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HederaEvmTransactionResultTest {
    @Mock
    private MessageFrame frame;

    @Mock
    private Deque<MessageFrame> stack;

    @Mock
    private ProxyWorldUpdater proxyWorldUpdater;

    @Mock
    private RootProxyWorldUpdater rootProxyWorldUpdater;

    @Mock
    private StorageAccessTracker accessTracker;

    @Mock
    private ActionSidecarContentTracer tracer;

    @Test
    void finalStatusFromHaltUsesCorrespondingStatusIfFromCustom() {
        givenFrameWithoutSidecars();
        given(frame.getGasPrice()).willReturn(WEI_NETWORK_GAS_PRICE);
        given(frame.getExceptionalHaltReason()).willReturn(Optional.of(SELF_DESTRUCT_TO_SELF));
        final var subject = HederaEvmTransactionResult.failureFrom(GAS_LIMIT / 2, SENDER_ID, frame, null, tracer);
        assertEquals(OBTAINER_SAME_CONTRACT_ID, subject.finalStatus());
        final var protoResult = subject.asProtoResultOf(null, rootProxyWorldUpdater, null);
        assertEquals(SELF_DESTRUCT_TO_SELF.toString(), protoResult.errorMessage());
    }

    @Test
    void finalStatusFromHaltUsesCorrespondingStatusIfFromStandard() {
        givenFrameWithAllSidecarsEnabled();
        given(frame.getGasPrice()).willReturn(WEI_NETWORK_GAS_PRICE);
        given(frame.getExceptionalHaltReason()).willReturn(Optional.of(ExceptionalHaltReason.INSUFFICIENT_GAS));
        final var subject = HederaEvmTransactionResult.failureFrom(GAS_LIMIT / 2, SENDER_ID, frame, null, tracer);
        assertEquals(INSUFFICIENT_GAS, subject.finalStatus());
        final var protoResult = subject.asProtoResultOf(null, rootProxyWorldUpdater, null);
        assertEquals(ExceptionalHaltReason.INSUFFICIENT_GAS.toString(), protoResult.errorMessage());
    }

    @Test
    void finalStatusFromInsufficientGasHaltImplemented() {
        givenFrameWithoutSidecars();
        given(frame.getGasPrice()).willReturn(WEI_NETWORK_GAS_PRICE);
        given(frame.getExceptionalHaltReason()).willReturn(Optional.of(ExceptionalHaltReason.INSUFFICIENT_GAS));
        final var subject = HederaEvmTransactionResult.failureFrom(GAS_LIMIT / 2, SENDER_ID, frame, null, tracer);
        assertEquals(ResponseCodeEnum.INSUFFICIENT_GAS, subject.finalStatus());
        verifyNoInteractions(tracer);
    }

    @Test
    void finalStatusFromMissingAddressHaltImplemented() {
        givenFrameWithAllSidecarsEnabled();
        given(frame.getGasPrice()).willReturn(WEI_NETWORK_GAS_PRICE);
        given(frame.getExceptionalHaltReason())
                .willReturn(Optional.of(CustomExceptionalHaltReason.INVALID_SOLIDITY_ADDRESS));
        final var subject = HederaEvmTransactionResult.failureFrom(GAS_LIMIT / 2, SENDER_ID, frame, null, tracer);
        assertEquals(ResponseCodeEnum.INVALID_SOLIDITY_ADDRESS, subject.finalStatus());
    }

    @Test
    void finalStatusFromWrongNonceAbortTranslated() {
        final var subject = HederaEvmTransactionResult.fromAborted(SENDER_ID, CALLED_CONTRACT_ID, WRONG_NONCE);
        assertEquals(WRONG_NONCE, subject.finalStatus());
    }

    @Test
    void finalStatusFromInvalidAccountIdAbortTranslated() {
        final var subject = HederaEvmTransactionResult.fromAborted(SENDER_ID, CALLED_CONTRACT_ID, INVALID_ACCOUNT_ID);
        assertEquals(INVALID_ACCOUNT_ID, subject.finalStatus());
    }

    @Test
    void finalStatusFromExecutionExceptionAbortTranslated() {
        final var subject =
                HederaEvmTransactionResult.fromAborted(SENDER_ID, CALLED_CONTRACT_ID, CONTRACT_EXECUTION_EXCEPTION);
        assertEquals(CONTRACT_EXECUTION_EXCEPTION, subject.finalStatus());
    }

    @Test
    void finalStatusFromIpbAbortTranslated() {
        final var subject =
                HederaEvmTransactionResult.fromAborted(SENDER_ID, CALLED_CONTRACT_ID, INSUFFICIENT_PAYER_BALANCE);
        assertEquals(INSUFFICIENT_PAYER_BALANCE, subject.finalStatus());
    }

    @Test
    void givenAccessTrackerIncludesFullContractStorageChangesAndNonNullNoncesOnSuccess() {
        givenFrameWithAllSidecarsEnabled();
        given(frame.getWorldUpdater()).willReturn(proxyWorldUpdater);
        final var pendingWrites = List.of(TWO_STORAGE_ACCESSES);
        given(proxyWorldUpdater.pendingStorageUpdates()).willReturn(pendingWrites);
        given(accessTracker.getReadsMergedWith(pendingWrites)).willReturn(SOME_STORAGE_ACCESSES);
        given(frame.getGasPrice()).willReturn(WEI_NETWORK_GAS_PRICE);
        given(frame.getLogs()).willReturn(BESU_LOGS);
        given(frame.getOutputData()).willReturn(pbjToTuweniBytes(OUTPUT_DATA));
        final var createdIds = List.of(CALLED_CONTRACT_ID, CHILD_CONTRACT_ID);
        given(rootProxyWorldUpdater.getCreatedContractIds()).willReturn(createdIds);
        given(rootProxyWorldUpdater.getUpdatedContractNonces()).willReturn(NONCES);
        given(rootProxyWorldUpdater.entityIdFactory()).willReturn(entityIdFactory);

        final var result = HederaEvmTransactionResult.successFrom(
                GAS_LIMIT / 2,
                SENDER_ID,
                CALLED_CONTRACT_ID,
                CALLED_CONTRACT_EVM_ADDRESS,
                frame,
                tracer,
                entityIdFactory);
        final var protoResult = result.asProtoResultOf(null, rootProxyWorldUpdater, null);
        assertEquals(GAS_LIMIT / 2, protoResult.gasUsed());
        assertEquals(bloomForAll(BESU_LOGS), protoResult.bloom());
        assertEquals(OUTPUT_DATA, protoResult.contractCallResult());
        assertEquals("", protoResult.errorMessage());
        assertNull(protoResult.senderId());
        assertEquals(CALLED_CONTRACT_ID, protoResult.contractID());
        assertEquals(pbjLogsFrom(entityIdFactory, BESU_LOGS), protoResult.logInfo());
        assertEquals(createdIds, protoResult.createdContractIDs());
        assertEquals(CALLED_CONTRACT_EVM_ADDRESS.evmAddressOrThrow(), protoResult.evmAddress());
        assertEquals(NONCES, protoResult.contractNonces());

        final var expectedChanges = ConversionUtils.asPbjStateChanges(SOME_STORAGE_ACCESSES);
        assertEquals(expectedChanges, result.stateChanges());
        assertEquals(SUCCESS, result.finalStatus());
    }

    @Test
    void givenEthTxDataIncludesSpecialFields() {
        givenFrameWithAllSidecarsEnabled();
        given(frame.getWorldUpdater()).willReturn(proxyWorldUpdater);
        final var pendingWrites = List.of(TWO_STORAGE_ACCESSES);
        given(proxyWorldUpdater.pendingStorageUpdates()).willReturn(pendingWrites);
        given(accessTracker.getReadsMergedWith(pendingWrites)).willReturn(SOME_STORAGE_ACCESSES);
        given(frame.getGasPrice()).willReturn(WEI_NETWORK_GAS_PRICE);
        given(frame.getLogs()).willReturn(BESU_LOGS);
        given(frame.getOutputData()).willReturn(pbjToTuweniBytes(OUTPUT_DATA));
        final var createdIds = List.of(CALLED_CONTRACT_ID, CHILD_CONTRACT_ID);
        given(rootProxyWorldUpdater.getCreatedContractIds()).willReturn(createdIds);
        given(rootProxyWorldUpdater.getUpdatedContractNonces()).willReturn(NONCES);
        given(rootProxyWorldUpdater.entityIdFactory()).willReturn(entityIdFactory);

        final var result = HederaEvmTransactionResult.successFrom(
                GAS_LIMIT / 2,
                SENDER_ID,
                CALLED_CONTRACT_ID,
                CALLED_CONTRACT_EVM_ADDRESS,
                frame,
                tracer,
                entityIdFactory);
        final var protoResult = result.asProtoResultOf(
                ETH_DATA_WITH_TO_ADDRESS, rootProxyWorldUpdater, Bytes.wrap(ETH_DATA_WITH_TO_ADDRESS.callData()));
        assertEquals(ETH_DATA_WITH_TO_ADDRESS.gasLimit(), protoResult.gas());
        assertEquals(ETH_DATA_WITH_TO_ADDRESS.getAmount(), protoResult.amount());
        assertArrayEquals(
                ETH_DATA_WITH_TO_ADDRESS.callData(),
                protoResult.functionParameters().toByteArray());
        assertEquals(SENDER_ID, protoResult.senderId());
        assertEquals(GAS_LIMIT / 2, protoResult.gasUsed());
        assertEquals(bloomForAll(BESU_LOGS), protoResult.bloom());
        assertEquals(OUTPUT_DATA, protoResult.contractCallResult());
        assertEquals("", protoResult.errorMessage());
        assertEquals(CALLED_CONTRACT_ID, protoResult.contractID());
        assertEquals(pbjLogsFrom(entityIdFactory, BESU_LOGS), protoResult.logInfo());
        assertEquals(createdIds, protoResult.createdContractIDs());
        assertEquals(CALLED_CONTRACT_EVM_ADDRESS.evmAddressOrThrow(), protoResult.evmAddress());
        assertEquals(NONCES, protoResult.contractNonces());

        final var expectedChanges = ConversionUtils.asPbjStateChanges(SOME_STORAGE_ACCESSES);
        assertEquals(expectedChanges, result.stateChanges());
        assertEquals(SUCCESS, result.finalStatus());
    }

    @Test
    void givenAccessTrackerIncludesReadStorageAccessesOnlyOnFailure() {
        givenFrameWithAllSidecarsEnabled();
        given(accessTracker.getJustReads()).willReturn(SOME_STORAGE_ACCESSES);
        given(frame.getGasPrice()).willReturn(WEI_NETWORK_GAS_PRICE);

        final var result = HederaEvmTransactionResult.failureFrom(GAS_LIMIT / 2, SENDER_ID, frame, null, tracer);

        final var expectedChanges = ConversionUtils.asPbjStateChanges(SOME_STORAGE_ACCESSES);
        assertEquals(expectedChanges, result.stateChanges());
    }

    @Test
    void withoutAccessTrackerReturnsNullStateChanges() {
        givenFrameWithoutSidecars();
        given(frame.getGasPrice()).willReturn(WEI_NETWORK_GAS_PRICE);
        given(frame.getOutputData()).willReturn(pbjToTuweniBytes(OUTPUT_DATA));

        final var result = HederaEvmTransactionResult.successFrom(
                GAS_LIMIT / 2,
                SENDER_ID,
                CALLED_CONTRACT_ID,
                CALLED_CONTRACT_EVM_ADDRESS,
                frame,
                tracer,
                entityIdFactory);

        assertNull(result.stateChanges());
    }

    @Test
    void QueryResultOnSuccess() {
        givenFrameWithDefaultConfigNoAccessTracker();
        given(frame.getGasPrice()).willReturn(WEI_NETWORK_GAS_PRICE);
        given(frame.getLogs()).willReturn(BESU_LOGS);
        given(frame.getOutputData()).willReturn(pbjToTuweniBytes(OUTPUT_DATA));
        given(proxyWorldUpdater.entityIdFactory()).willReturn(entityIdFactory);

        final var result = HederaEvmTransactionResult.successFrom(
                GAS_LIMIT / 2,
                SENDER_ID,
                CALLED_CONTRACT_ID,
                CALLED_CONTRACT_EVM_ADDRESS,
                frame,
                tracer,
                entityIdFactory);
        final var queryResult = result.asQueryResult(proxyWorldUpdater);
        assertEquals(GAS_LIMIT / 2, queryResult.gasUsed());
        assertEquals(bloomForAll(BESU_LOGS), queryResult.bloom());
        assertEquals(OUTPUT_DATA, queryResult.contractCallResult());
        assertEquals("", queryResult.errorMessage());
        assertNull(queryResult.senderId());
        assertEquals(CALLED_CONTRACT_ID, queryResult.contractID());
        assertEquals(pbjLogsFrom(entityIdFactory, BESU_LOGS), queryResult.logInfo());
    }

    @Test
    void QueryResultOnHalt() {
        givenFrameWithoutSidecars();
        given(frame.getGasPrice()).willReturn(WEI_NETWORK_GAS_PRICE);
        given(frame.getExceptionalHaltReason()).willReturn(Optional.of(ExceptionalHaltReason.INVALID_OPERATION));

        final var result = HederaEvmTransactionResult.failureFrom(GAS_LIMIT / 2, SENDER_ID, frame, null, tracer);
        final var protoResult = result.asQueryResult(proxyWorldUpdater);
        assertEquals(ExceptionalHaltReason.INVALID_OPERATION.toString(), protoResult.errorMessage());
    }

    @Test
    void QueryResultOnRevert() {
        givenFrameWithoutSidecars();
        given(frame.getGasPrice()).willReturn(WEI_NETWORK_GAS_PRICE);
        given(frame.getRevertReason()).willReturn(Optional.of(SOME_REVERT_REASON));

        final var result = HederaEvmTransactionResult.failureFrom(GAS_LIMIT / 2, SENDER_ID, frame, null, tracer);
        final var protoResult = result.asQueryResult(proxyWorldUpdater);
        assertEquals(SOME_REVERT_REASON.toString(), protoResult.errorMessage());
    }

    private void givenFrameWithDegenerateStack() {
        given(frame.getMessageFrameStack()).willReturn(stack);
        given(stack.isEmpty()).willReturn(true);
    }

    private void givenFrameWithDefaultConfigNoAccessTracker() {
        givenDefaultConfigInFrame(frame);
        doReturn(null).when(frame).getContextVariable(TRACKER_CONTEXT_VARIABLE);
    }

    private void givenFrameWithAllSidecarsEnabled() {
        givenDefaultConfigInFrame(frame);
        doReturn(accessTracker).when(frame).getContextVariable(TRACKER_CONTEXT_VARIABLE);
    }

    private void givenFrameWithoutSidecars() {
        givenConfigInFrame(
                frame,
                HederaTestConfigBuilder.create()
                        .withValue("contracts.sidecars", "CONTRACT_STATE_CHANGE,CONTRACT_BYTECODE")
                        .getOrCreateConfig());
        doReturn(null).when(frame).getContextVariable(TRACKER_CONTEXT_VARIABLE);
    }
}
