// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.operations;

import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.INVALID_SOLIDITY_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.*;
import static org.hyperledger.besu.evm.frame.ExceptionalHaltReason.INSUFFICIENT_GAS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doCallRealMethod;

import com.hedera.node.app.service.contract.impl.exec.AddressChecks;
import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import com.hedera.node.app.service.contract.impl.exec.operations.CustomStaticCallOperation;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.exec.utils.InvalidAddressContext;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
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
class CustomStaticCallOperationTest {

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
    private FeatureFlags featureFlags;

    @Mock
    private ProxyWorldUpdater updater;

    private CustomStaticCallOperation subject;

    @BeforeEach
    void setUp() {
        subject = new CustomStaticCallOperation(gasCalculator, addressChecks, featureFlags);
    }

    @Test
    void catchesUnderflowWhenStackIsEmpty() {
        givenWellKnownFrameWithNoGasCalc(1, NON_SYSTEM_LONG_ZERO_ADDRESS, 2L);
        given(frame.getStackItem(1)).willThrow(UnderflowException.class);
        final var expected = new Operation.OperationResult(0L, ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS);
        assertSameResult(expected, subject.execute(frame, evm));
    }

    @Test
    void rejectsMissingNonSystemAddress() {
        try (MockedStatic<FrameUtils> frameUtils = Mockito.mockStatic(FrameUtils.class)) {
            doCallRealMethod().when(addressChecks).isNeitherSystemNorPresent(any(), any());
            givenWellKnownFrameWith(1L, NON_SYSTEM_LONG_ZERO_ADDRESS, 2L);
            frameUtils.when(() -> FrameUtils.invalidAddressContext(frame)).thenReturn(new InvalidAddressContext());
            frameUtils.when(() -> FrameUtils.proxyUpdaterFor(frame)).thenReturn(updater);
            frameUtils
                    .when(() -> FrameUtils.contractRequired(frame, NON_SYSTEM_LONG_ZERO_ADDRESS, featureFlags))
                    .thenReturn(true);
            final var expected = new Operation.OperationResult(REQUIRED_GAS, INVALID_SOLIDITY_ADDRESS);
            assertSameResult(expected, subject.execute(frame, evm));
        }
    }

    @Test
    void permitsSystemAddress() {
        try (MockedStatic<FrameUtils> frameUtils = Mockito.mockStatic(FrameUtils.class)) {
            givenWellKnownFrameWith(1L, SYSTEM_ADDRESS, 2L);
            given(frame.getRemainingGas()).willReturn(0L);
            given(gasCalculator.callOperationGasCost(
                            any(),
                            anyLong(),
                            anyLong(),
                            anyLong(),
                            anyLong(),
                            anyLong(),
                            any(),
                            any(),
                            any(),
                            anyBoolean()))
                    .willReturn(REQUIRED_GAS);
            frameUtils.when(() -> FrameUtils.proxyUpdaterFor(frame)).thenReturn(updater);
            final var expected = new Operation.OperationResult(REQUIRED_GAS, INSUFFICIENT_GAS);
            assertSameResult(expected, subject.execute(frame, evm));
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
    }

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
                        any(),
                        anyBoolean()))
                .willReturn(REQUIRED_GAS);
    }
}
