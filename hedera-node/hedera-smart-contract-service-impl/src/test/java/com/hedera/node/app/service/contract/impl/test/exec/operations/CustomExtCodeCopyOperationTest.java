// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.operations;

import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.INVALID_SOLIDITY_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.GAS_LIMIT;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.assertSameResult;
import static org.hyperledger.besu.evm.frame.ExceptionalHaltReason.INSUFFICIENT_GAS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.node.app.service.contract.impl.exec.AddressChecks;
import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import com.hedera.node.app.service.contract.impl.exec.operations.CustomExtCodeCopyOperation;
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
class CustomExtCodeCopyOperationTest {
    private static final String HEX_ADDRESS_STR = "0x123";
    private static final Address HEX_ADDRESS = Address.fromHexString(HEX_ADDRESS_STR);

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

    private CustomExtCodeCopyOperation subject;

    @BeforeEach
    void setUp() {
        subject = new CustomExtCodeCopyOperation(gasCalculator, addressChecks, featureFlags);
    }

    @Test
    void catchesUnderflowWhenStackIsEmpty() {
        given(frame.getStackItem(0)).willThrow(UnderflowException.class);
        final var expected = new Operation.OperationResult(0L, ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS);
        assertSameResult(expected, subject.execute(frame, evm));
    }

    @Test
    void rejectsMissingUserAddressIfAllowCallFeatureFlagOff() {
        try (MockedStatic<FrameUtils> frameUtils = Mockito.mockStatic(FrameUtils.class)) {
            given(frame.getStackItem(1)).willReturn(Bytes32.leftPad(Bytes.ofUnsignedLong(1L)));
            given(frame.getStackItem(3)).willReturn(Bytes32.leftPad(Bytes.ofUnsignedLong(3)));
            givenWellKnownFrameWith(Address.fromHexString(HEX_ADDRESS_STR));
            given(frame.getRemainingGas()).willReturn(GAS_LIMIT);
            frameUtils.when(() -> FrameUtils.invalidAddressContext(frame)).thenReturn(new InvalidAddressContext());
            frameUtils.when(() -> FrameUtils.proxyUpdaterFor(frame)).thenReturn(updater);
            frameUtils
                    .when(() -> FrameUtils.contractRequired(any(), any(), any()))
                    .thenReturn(true);
            final var expected = new Operation.OperationResult(123L, INVALID_SOLIDITY_ADDRESS);
            assertSameResult(expected, subject.execute(frame, evm));
        }
    }

    @Test
    void delegatesToParentIfAllowCallFeatureFlagOn() {
        try (MockedStatic<FrameUtils> frameUtils = Mockito.mockStatic(FrameUtils.class)) {
            given(frame.getStackItem(1)).willReturn(Bytes32.leftPad(Bytes.ofUnsignedLong(1L)));
            given(frame.getStackItem(3)).willReturn(Bytes32.leftPad(Bytes.ofUnsignedLong(3)));
            givenWellKnownFrameWith(Address.fromHexString(HEX_ADDRESS_STR));
            frameUtils.when(() -> FrameUtils.proxyUpdaterFor(frame)).thenReturn(updater);
            final var expected = new Operation.OperationResult(123L, INSUFFICIENT_GAS);
            assertSameResult(expected, subject.execute(frame, evm));
        }
    }

    @Test
    void hasSpecialBehaviorForNonUserAccount() {
        given(addressChecks.isNonUserAccount(Address.fromHexString(HEX_ADDRESS_STR)))
                .willReturn(true);
        given(frame.getStackItem(anyInt())).willReturn(Bytes32.leftPad(Bytes.ofUnsignedLong(1)));
        givenWellKnownFrameWith(HEX_ADDRESS);
        given(frame.getRemainingGas()).willReturn(GAS_LIMIT);
        final var expected = new Operation.OperationResult(123L, null);
        assertSameResult(expected, subject.execute(frame, evm));
        verify(frame).popStackItems(4);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void nonUserAccountChargedAtWarmCostOnlyWhenWarm(final boolean isWarm) {
        given(addressChecks.isNonUserAccount(HEX_ADDRESS)).willReturn(true);
        given(frame.getStackItem(anyInt())).willReturn(Bytes32.leftPad(Bytes.ofUnsignedLong(1)));
        givenWellKnownFrameWith(HEX_ADDRESS);
        if (isWarm) {
            given(gasCalculator.getWarmStorageReadCost()).willReturn(100L);
        } else {
            given(gasCalculator.getColdAccountAccessCost()).willReturn(2600L);
        }
        // Exactly the priced cost remains, so a warm access must not be halted for lacking the cold cost
        given(frame.getRemainingGas()).willReturn(isWarm ? 223L : 2723L);
        given(frame.isAddressWarm(HEX_ADDRESS)).willReturn(isWarm);
        final var expected = new Operation.OperationResult(isWarm ? 223L : 2723L, null);
        assertSameResult(expected, subject.execute(frame, evm));
        verify(frame).warmUpAddress(HEX_ADDRESS);
        verify(frame).popStackItems(4);
    }

    @Test
    void precompileIsAlwaysWarm() {
        final var precompile = Address.fromHexString("0x09");
        given(addressChecks.isNonUserAccount(precompile)).willReturn(true);
        given(frame.getStackItem(anyInt())).willReturn(Bytes32.leftPad(Bytes.ofUnsignedLong(1)));
        givenWellKnownFrameWith(precompile);
        given(gasCalculator.getWarmStorageReadCost()).willReturn(100L);
        given(gasCalculator.isPrecompile(precompile)).willReturn(true);
        given(frame.getRemainingGas()).willReturn(223L);
        final var expected = new Operation.OperationResult(223L, null);
        assertSameResult(expected, subject.execute(frame, evm));
        verify(frame).popStackItems(4);
    }

    @Test
    void hasNormalBehaviorForUserAccount() {
        try (MockedStatic<FrameUtils> frameUtils = Mockito.mockStatic(FrameUtils.class)) {
            given(frame.getStackItem(anyInt())).willReturn(Bytes32.leftPad(Bytes.ofUnsignedLong(1)));
            given(frame.getRemainingGas()).willReturn(0L);
            given(gasCalculator.extCodeCopyOperationGasCost(eq(frame), anyLong(), anyLong()))
                    .willReturn(123L);
            frameUtils.when(() -> FrameUtils.proxyUpdaterFor(frame)).thenReturn(updater);
            final var expected = new Operation.OperationResult(123L, INSUFFICIENT_GAS);
            assertSameResult(expected, subject.execute(frame, evm));
        }
    }

    private void givenWellKnownFrameWith(final Address to) {
        given(frame.getStackItem(0)).willReturn(to.getBytes());
        given(gasCalculator.extCodeCopyOperationGasCost(eq(frame), anyLong(), anyLong()))
                .willReturn(123L);
    }
}
