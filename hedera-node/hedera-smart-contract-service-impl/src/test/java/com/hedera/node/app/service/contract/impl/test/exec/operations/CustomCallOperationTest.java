// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.operations;

import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.INVALID_SOLIDITY_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.EIP_1014_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.REQUIRED_GAS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SYSTEM_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.assertSameResult;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.service.contract.impl.exec.AddressChecks;
import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import com.hedera.node.app.service.contract.impl.exec.operations.CustomCallOperation;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.service.contract.impl.test.TestHelpers;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.internal.UnderflowException;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CustomCallOperationTest {
    @Mock
    private FeatureFlags featureFlags;

    @Mock
    private GasCalculator gasCalculator;

    @Mock
    private AddressChecks addressChecks;

    @Mock
    private MessageFrame frame;

    @Mock
    private WorldUpdater worldUpdater;

    @Mock
    private EVM evm;

    @Mock
    private ProxyWorldUpdater updater;

    @Mock
    private EntityIdFactory entityIdFactory;

    private CustomCallOperation subject;

    @BeforeEach
    void setUp() {
        subject = new CustomCallOperation(featureFlags, gasCalculator, addressChecks);
    }

    @Test
    void withImplicitCreationEnabledDoesNoFurtherChecks() {
        try (MockedStatic<FrameUtils> frameUtils = Mockito.mockStatic(FrameUtils.class)) {
            givenWellKnownFrameWith(1L, TestHelpers.EIP_1014_ADDRESS, 2L);
            given(frame.isStatic()).willReturn(true);
            frameUtils.when(() -> FrameUtils.proxyUpdaterFor(frame)).thenReturn(updater);
            frameUtils.when(() -> FrameUtils.entityIdFactory(frame)).thenReturn(entityIdFactory);

            final var expected =
                    new Operation.OperationResult(REQUIRED_GAS, ExceptionalHaltReason.ILLEGAL_STATE_CHANGE);
            final var actual = subject.execute(frame, evm);

            assertSameResult(expected, actual);
        }
    }

    @Test
    void returnsUnderflowResponse() {
        given(frame.getStackItem(anyInt())).willThrow(UnderflowException.class);
        final var expected = new Operation.OperationResult(0, ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS);
        final var actual = subject.execute(frame, evm);

        assertSameResult(expected, actual);
    }

    @Test
    void withPresentEip1014ContinuesAsExpected() {
        try (MockedStatic<FrameUtils> frameUtils = Mockito.mockStatic(FrameUtils.class)) {
            givenWellKnownFrameWith(1L, TestHelpers.EIP_1014_ADDRESS, 2L);
            given(addressChecks.isPresent(EIP_1014_ADDRESS, frame)).willReturn(true);
            given(frame.isStatic()).willReturn(true);
            frameUtils.when(() -> FrameUtils.proxyUpdaterFor(frame)).thenReturn(updater);
            frameUtils.when(() -> FrameUtils.entityIdFactory(frame)).thenReturn(entityIdFactory);

            final var expected =
                    new Operation.OperationResult(REQUIRED_GAS, ExceptionalHaltReason.ILLEGAL_STATE_CHANGE);
            final var actual = subject.execute(frame, evm);

            assertSameResult(expected, actual);
        }
    }

    @Test
    void withSystemAccountContinuesAsExpected() {
        givenWellKnownFrameWithNoGasCalc(1L, SYSTEM_ADDRESS, 2L);
        given(frame.getStackItem(1)).willReturn(SYSTEM_ADDRESS);
        given(frame.getWorldUpdater()).willReturn(updater);
        given(addressChecks.isSystemAccount(SYSTEM_ADDRESS)).willReturn(true);

        final var expected = new Operation.OperationResult(0, ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS);
        final var actual = subject.execute(frame, evm);

        assertSameResult(expected, actual);
    }

    @Test
    void withNoValueRejectsMissingAddressIfAllowCallFeatureFlagOff() {
        try (MockedStatic<FrameUtils> frameUtils = Mockito.mockStatic(FrameUtils.class)) {
            givenWellKnownFrameWith(0L, TestHelpers.EIP_1014_ADDRESS, 2L);
            frameUtils.when(() -> FrameUtils.proxyUpdaterFor(frame)).thenReturn(updater);
            frameUtils.when(() -> FrameUtils.entityIdFactory(frame)).thenReturn(entityIdFactory);
            frameUtils
                    .when(() -> FrameUtils.contractRequired(frame, EIP_1014_ADDRESS, featureFlags))
                    .thenReturn(true);

            final var expected = new Operation.OperationResult(REQUIRED_GAS, INVALID_SOLIDITY_ADDRESS);
            final var actual = subject.execute(frame, evm);

            assertSameResult(expected, actual);
        }
    }

    @Test
    void delegateToParentMissingAddressIfAllowCallFeatureFlagOn() {
        try (MockedStatic<FrameUtils> frameUtils = Mockito.mockStatic(FrameUtils.class)) {
            givenWellKnownFrameWithNoGasCalc(1L, EIP_1014_ADDRESS, 2L);
            given(frame.getStackItem(1)).willReturn(TestHelpers.EIP_1014_ADDRESS);
            given(frame.getStackItem(2)).willReturn(Bytes32.leftPad(Bytes.ofUnsignedLong(2l)));
            frameUtils.when(() -> FrameUtils.proxyUpdaterFor(frame)).thenReturn(updater);

            final var expected = new Operation.OperationResult(0, ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS);
            final var actual = subject.execute(frame, evm);

            assertSameResult(expected, actual);
        }
    }

    @Test
    void withoutImplicitCreationEnabledRejectsMissingAddress() {
        try (MockedStatic<FrameUtils> frameUtils = Mockito.mockStatic(FrameUtils.class)) {
            givenWellKnownFrameWith(1L, TestHelpers.EIP_1014_ADDRESS, 2L);
            frameUtils.when(() -> FrameUtils.proxyUpdaterFor(frame)).thenReturn(updater);
            frameUtils.when(() -> FrameUtils.entityIdFactory(frame)).thenReturn(entityIdFactory);
            frameUtils
                    .when(() -> FrameUtils.contractRequired(frame, TestHelpers.EIP_1014_ADDRESS, featureFlags))
                    .thenReturn(true);

            final var expected = new Operation.OperationResult(REQUIRED_GAS, INVALID_SOLIDITY_ADDRESS);
            final var actual = subject.execute(frame, evm);

            assertSameResult(expected, actual);
        }
    }

    private void givenWellKnownFrameWithNoGasCalc(final long value, final Address to, final long gas) {
        given(frame.getWorldUpdater()).willReturn(worldUpdater);
        given(frame.getStackItem(0)).willReturn(Bytes32.leftPad(Bytes.ofUnsignedLong(gas)));
        given(frame.getStackItem(1)).willReturn(to);
        given(frame.getStackItem(2)).willReturn(Bytes32.leftPad(Bytes.ofUnsignedLong(value)));
        given(frame.getStackItem(3)).willReturn(Bytes32.leftPad(Bytes.ofUnsignedLong(3)));
        given(frame.getStackItem(4)).willReturn(Bytes32.leftPad(Bytes.ofUnsignedLong(4)));
        given(frame.getStackItem(5)).willReturn(Bytes32.leftPad(Bytes.ofUnsignedLong(5)));
        given(frame.getStackItem(6)).willReturn(Bytes32.leftPad(Bytes.ofUnsignedLong(6)));
    }

    /**
     * Return a frame with the given gas, to address, and value as the top three stack items.
     */
    private void givenWellKnownFrameWith(final long value, final Address to, final long gas) {
        givenWellKnownFrameWithNoGasCalc(value, to, gas);
        given(frame.getRemainingGas()).willReturn(REQUIRED_GAS);
        given(gasCalculator.callOperationGasCost(
                        any(),
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        any(),
                        any(),
                        eq(to),
                        anyBoolean()))
                .willReturn(REQUIRED_GAS);
    }
}
