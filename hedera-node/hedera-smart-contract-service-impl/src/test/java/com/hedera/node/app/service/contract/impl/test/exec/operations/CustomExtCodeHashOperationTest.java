// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.operations;

import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.INVALID_SOLIDITY_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.assertSameResult;
import static org.hyperledger.besu.evm.frame.ExceptionalHaltReason.INSUFFICIENT_GAS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.node.app.service.contract.impl.exec.AddressChecks;
import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import com.hedera.node.app.service.contract.impl.exec.operations.CustomExtCodeHashOperation;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.exec.utils.InvalidAddressContext;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.internal.UnderflowException;
import org.hyperledger.besu.evm.operation.Operation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CustomExtCodeHashOperationTest {
    @Mock
    private GasCalculator gasCalculator;

    @Mock
    private AddressChecks addressChecks;

    @Mock
    private MessageFrame frame;

    @Mock
    private EVM evm;

    @Mock
    private FeatureFlags featureFlags;

    @Mock
    private ProxyWorldUpdater updater;

    private CustomExtCodeHashOperation subject;

    @BeforeEach
    void setUp() {
        subject = new CustomExtCodeHashOperation(gasCalculator, addressChecks, featureFlags);
    }

    @Test
    void catchesUnderflowWhenStackIsEmpty() {
        given(frame.getStackItem(0)).willThrow(UnderflowException.class);
        final var expected = new Operation.OperationResult(0L, ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS);
        assertSameResult(expected, subject.execute(frame, evm));
    }

    @Test
    void rejectsMissingNonSystemAddressIfAllowCallFeatureFlagOff() {
        try (MockedStatic<FrameUtils> frameUtils = Mockito.mockStatic(FrameUtils.class)) {
            givenWellKnownFrameWith();
            frameUtils.when(() -> FrameUtils.invalidAddressContext(frame)).thenReturn(new InvalidAddressContext());
            given(frame.getRemainingGas()).willReturn(123L);
            given(frame.getStackItem(0)).willReturn(Address.fromHexString("0x123"));
            frameUtils.when(() -> FrameUtils.proxyUpdaterFor(frame)).thenReturn(updater);
            frameUtils
                    .when(() -> FrameUtils.contractRequired(any(), any(), any()))
                    .thenReturn(true);
            final var expected = new Operation.OperationResult(123L, INVALID_SOLIDITY_ADDRESS);
            assertSameResult(expected, subject.execute(frame, evm));
        }
    }

    @Test
    void deletesToParentIfAllowCallFeatureFlagOn() {
        try (MockedStatic<FrameUtils> frameUtils = Mockito.mockStatic(FrameUtils.class)) {
            givenWellKnownFrameWith();
            frameUtils.when(() -> FrameUtils.proxyUpdaterFor(frame)).thenReturn(updater);
            final var expected = new Operation.OperationResult(123L, INSUFFICIENT_GAS);
            assertSameResult(expected, subject.execute(frame, evm));
        }
    }

    @Test
    void hasSpecialBehaviorForNonUserAccount() {
        given(frame.getStackItem(0)).willReturn(Address.fromHexString("0x123"));
        given(addressChecks.isNonUserAccount(Address.fromHexString("0x123"))).willReturn(true);
        givenWellKnownFrameWith();
        given(frame.getRemainingGas()).willReturn(123L);
        final var expected = new Operation.OperationResult(123L, null);
        assertSameResult(expected, subject.execute(frame, evm));
        verify(frame).popStackItem();
        verify(frame).pushStackItem(UInt256.ZERO);
    }

    @Test
    void delegatesForPresentAddress() {
        try (MockedStatic<FrameUtils> frameUtils = Mockito.mockStatic(FrameUtils.class)) {
            givenWellKnownFrameWith();
            frameUtils.when(() -> FrameUtils.proxyUpdaterFor(frame)).thenReturn(updater);
            final var expected = new Operation.OperationResult(123L, INSUFFICIENT_GAS);
            assertSameResult(expected, subject.execute(frame, evm));
        }
    }

    private void givenWellKnownFrameWith() {
        given(gasCalculator.extCodeHashOperationGasCost()).willReturn(123L);
    }
}
