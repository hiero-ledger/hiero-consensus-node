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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
            given(frame.getStackItem(0))
                    .willReturn(Address.fromHexString("0x123").getBytes());
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
            given(frame.getStackItem(0))
                    .willReturn(Address.fromHexString("0x123").getBytes());
            frameUtils.when(() -> FrameUtils.proxyUpdaterFor(frame)).thenReturn(updater);
            final var expected = new Operation.OperationResult(123L, INSUFFICIENT_GAS);
            assertSameResult(expected, subject.execute(frame, evm));
        }
    }

    @Test
    void hasSpecialBehaviorForNonUserAccount() {
        given(frame.getStackItem(0)).willReturn(Address.fromHexString("0x123").getBytes());
        given(addressChecks.isNonUserAccount(Address.fromHexString("0x123"))).willReturn(true);
        givenWellKnownFrameWith();
        given(frame.getRemainingGas()).willReturn(123L);
        final var expected = new Operation.OperationResult(123L, null);
        assertSameResult(expected, subject.execute(frame, evm));
        verify(frame).popStackItem();
        verify(frame).pushStackItem(UInt256.ZERO);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void nonUserAccountChargedAtWarmCostOnlyWhenWarm(final boolean isWarm) {
        final var address = Address.fromHexString("0x123");
        given(frame.getStackItem(0)).willReturn(address.getBytes());
        given(addressChecks.isNonUserAccount(address)).willReturn(true);
        givenWellKnownFrameWith();
        if (isWarm) {
            given(gasCalculator.getWarmStorageReadCost()).willReturn(100L);
        } else {
            given(gasCalculator.getColdAccountAccessCost()).willReturn(2600L);
        }
        // Exactly the priced cost remains, so a warm access must not be halted for lacking the cold cost
        given(frame.getRemainingGas()).willReturn(isWarm ? 223L : 2723L);
        given(frame.isAddressWarm(address)).willReturn(isWarm);
        final var expected = new Operation.OperationResult(isWarm ? 223L : 2723L, null);
        assertSameResult(expected, subject.execute(frame, evm));
        verify(frame).warmUpAddress(address);
        verify(frame).pushStackItem(UInt256.ZERO);
    }

    @Test
    void precompileIsAlwaysWarm() {
        final var precompile = Address.fromHexString("0x09");
        given(frame.getStackItem(0)).willReturn(precompile.getBytes());
        given(addressChecks.isNonUserAccount(precompile)).willReturn(true);
        givenWellKnownFrameWith();
        given(gasCalculator.getWarmStorageReadCost()).willReturn(100L);
        given(gasCalculator.isPrecompile(precompile)).willReturn(true);
        given(frame.getRemainingGas()).willReturn(223L);
        final var expected = new Operation.OperationResult(223L, null);
        assertSameResult(expected, subject.execute(frame, evm));
        verify(frame).pushStackItem(UInt256.ZERO);
    }

    @Test
    void delegatesForPresentAddress() {
        try (MockedStatic<FrameUtils> frameUtils = Mockito.mockStatic(FrameUtils.class)) {
            givenWellKnownFrameWith();
            given(frame.getStackItem(0))
                    .willReturn(Address.fromHexString("0x123").getBytes());
            frameUtils.when(() -> FrameUtils.proxyUpdaterFor(frame)).thenReturn(updater);
            final var expected = new Operation.OperationResult(123L, INSUFFICIENT_GAS);
            assertSameResult(expected, subject.execute(frame, evm));
        }
    }

    private void givenWellKnownFrameWith() {
        given(gasCalculator.extCodeHashOperationGasCost()).willReturn(123L);
    }
}
