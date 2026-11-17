// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.utils;

import static com.hedera.node.app.service.contract.impl.exec.utils.OperationUtils.isDeficientGas;
import static com.hedera.node.app.service.contract.impl.exec.utils.OperationUtils.isWarmAccess;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_LONG_ZERO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SYSTEM_ADDRESS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OperationUtilsTest {
    @Mock
    private MessageFrame frame;

    @Mock
    private GasCalculator gasCalculator;

    @Test
    void gasIsDeficientOnlyWhenLessRemainsThanTheCost() {
        given(frame.getRemainingGas()).willReturn(100L);
        assertTrue(isDeficientGas(frame, 101L));
        assertFalse(isDeficientGas(frame, 100L));
    }

    @Test
    void accessToAnAlreadyWarmAddressIsWarmWithoutWarmingIt() {
        given(frame.isAddressWarm(SYSTEM_ADDRESS)).willReturn(true);
        assertTrue(isWarmAccess(frame, SYSTEM_ADDRESS, gasCalculator));
        verify(frame, never()).warmUpAddress(SYSTEM_ADDRESS);
    }

    @Test
    void accessToAPrecompileIsWarm() {
        given(gasCalculator.isPrecompile(SYSTEM_ADDRESS)).willReturn(true);
        assertTrue(isWarmAccess(frame, SYSTEM_ADDRESS, gasCalculator));
    }

    @Test
    void accessToAnyOtherAddressIsCold() {
        assertFalse(isWarmAccess(frame, NON_SYSTEM_LONG_ZERO_ADDRESS, gasCalculator));
    }
}
