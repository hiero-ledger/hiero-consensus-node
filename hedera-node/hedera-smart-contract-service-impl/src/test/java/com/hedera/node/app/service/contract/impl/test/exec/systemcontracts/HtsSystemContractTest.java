// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.haltResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.revertResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_167_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.callTypeOf;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.contractsConfigOf;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.isDelegateCall;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.proxyUpdaterFor;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_CONTRACTS_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.assertSamePrecompileResult;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.SystemContractOperations;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallFactory;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.EntityType;
import com.hedera.node.app.service.contract.impl.hevm.HederaOpsDuration;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.spi.workflows.HandleException;
import java.nio.ByteBuffer;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HtsSystemContractTest {
    @Mock
    private Call call;

    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private MessageFrame frame;

    @Mock
    private ProxyWorldUpdater updater;

    @Mock
    private HederaWorldUpdater.Enhancement enhancement;

    @Mock
    private SystemContractOperations systemOperations;

    @Mock
    private HtsCallFactory attemptFactory;

    @Mock
    private GasCalculator gasCalculator;

    @Mock
    private ContractMetrics contractMetrics;

    @Mock
    private HederaOpsDuration hederaOpsDuration;

    private MockedStatic<FrameUtils> frameUtils;

    private HtsSystemContract subject;
    private final Bytes validInput = Bytes.fromHexString("91548228");

    @BeforeEach
    void setUp() {
        frameUtils = Mockito.mockStatic(FrameUtils.class);
        subject = new HtsSystemContract(gasCalculator, attemptFactory, contractMetrics, hederaOpsDuration);
    }

    @AfterEach
    void clear() {
        frameUtils.close();
    }

    @Test
    void returnsResultFromImpliedCall() {
        givenValidCallAttempt();
        frameUtils
                .when(() -> callTypeOf(frame, EntityType.TOKEN))
                .thenReturn(FrameUtils.CallType.DIRECT_OR_PROXY_REDIRECT);
        frameUtils.when(() -> contractsConfigOf(frame)).thenReturn(DEFAULT_CONTRACTS_CONFIG);

        final var pricedResult = gasOnly(successResult(ByteBuffer.allocate(1), 123L), SUCCESS, true);
        given(call.execute(frame)).willReturn(pricedResult);
        given(attempt.senderId()).willReturn(SENDER_ID);

        assertSame(pricedResult.fullResult(), subject.computeFully(HTS_167_CONTRACT_ID, validInput, frame));
    }

    @Test
    void invalidCallAttemptHaltsAndConsumesRemainingGas() {
        given(attemptFactory.createCallAttemptFrom(
                        HTS_167_CONTRACT_ID, Bytes.EMPTY, FrameUtils.CallType.DIRECT_OR_PROXY_REDIRECT, frame))
                .willThrow(RuntimeException.class);
        frameUtils.when(() -> contractsConfigOf(frame)).thenReturn(DEFAULT_CONTRACTS_CONFIG);
        final var expected = haltResult(ExceptionalHaltReason.INVALID_OPERATION, frame.getRemainingGas());
        final var result = subject.computeFully(HTS_167_CONTRACT_ID, validInput, frame);
        assertSamePrecompileResult(expected, result);
    }

    @Test
    void internalErrorAttemptHaltsAndConsumesRemainingGas() {
        givenValidCallAttempt();
        frameUtils
                .when(() -> callTypeOf(frame, EntityType.TOKEN))
                .thenReturn(FrameUtils.CallType.DIRECT_OR_PROXY_REDIRECT);
        frameUtils.when(() -> contractsConfigOf(frame)).thenReturn(DEFAULT_CONTRACTS_CONFIG);
        given(call.execute(frame)).willThrow(RuntimeException.class);

        final var expected = haltResult(ExceptionalHaltReason.PRECOMPILE_ERROR, frame.getRemainingGas());
        final var result = subject.computeFully(HTS_167_CONTRACT_ID, validInput, frame);
        assertSamePrecompileResult(expected, result);
    }

    @Test
    void testComputeFullyWithEmptyBytes() {
        frameUtils.when(() -> contractsConfigOf(frame)).thenReturn(DEFAULT_CONTRACTS_CONFIG);
        final var expected = haltResult(ExceptionalHaltReason.INVALID_OPERATION, frame.getRemainingGas());
        final var result = subject.computeFully(HTS_167_CONTRACT_ID, Bytes.EMPTY, frame);
        assertSamePrecompileResult(expected, result);
    }

    @Test
    void testComputeFullyWithHandleExceptionFromSystemContract() {
        givenValidCallAttempt();
        frameUtils
                .when(() -> callTypeOf(frame, EntityType.TOKEN))
                .thenReturn(FrameUtils.CallType.DIRECT_OR_PROXY_REDIRECT);
        frameUtils.when(() -> contractsConfigOf(frame)).thenReturn(DEFAULT_CONTRACTS_CONFIG);
        given(attempt.asExecutableCall()).willThrow(new HandleException(CONTRACT_REVERT_EXECUTED));
        final var expected = revertResult(CONTRACT_REVERT_EXECUTED, frame.getRemainingGas());
        final var result = subject.computeFully(HTS_167_CONTRACT_ID, validInput, frame);
        assertSamePrecompileResult(expected, result);
    }

    private void givenValidCallAttempt() {
        frameUtils.when(() -> isDelegateCall(frame)).thenReturn(false);
        frameUtils.when(() -> proxyUpdaterFor(frame)).thenReturn(updater);
        lenient().when(updater.enhancement()).thenReturn(enhancement);
        lenient().when(enhancement.systemOperations()).thenReturn(systemOperations);
        given(attemptFactory.createCallAttemptFrom(
                        HTS_167_CONTRACT_ID, validInput, FrameUtils.CallType.DIRECT_OR_PROXY_REDIRECT, frame))
                .willReturn(attempt);
        given(attempt.asExecutableCall()).willReturn(call);
    }
}
