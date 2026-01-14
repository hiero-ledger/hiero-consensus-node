// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.hevm;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.hedera.node.app.service.contract.impl.test.TestHelpers;
import org.junit.jupiter.api.Test;

class HederaEvmTransactionTest {
    @Test
    void gasAvailableIsLimitMinusIntrinsic() {
        final var subject = TestHelpers.wellKnownHapiCall();
        assertEquals(GAS_LIMIT - INTRINSIC_GAS, subject.gasAvailable(INTRINSIC_GAS));
    }

    @Test
    void computesUpfrontCostWithoutOverflowConcern() {
        final var subject = TestHelpers.wellKnownHapiCall();
        assertEquals(VALUE + 123L * GAS_LIMIT, subject.upfrontCostGiven(123L));
    }

    @Test
    void computesUpfrontCostWithOverflow() {
        final var subject = TestHelpers.wellKnownHapiCall();
        assertEquals(Long.MAX_VALUE, subject.upfrontCostGiven(Long.MAX_VALUE / (GAS_LIMIT - 1)));
    }

    @Test
    void computesOfferedGasCostWithoutOverflowConcern() {
        final var subject = TestHelpers.wellKnownHapiCall();
        assertEquals(GAS_LIMIT * USER_OFFERED_GAS_PRICE, subject.offeredGasCost());
    }

    @Test
    void computesOfferedGasCostWithOverflow() {
        final var subject = TestHelpers.wellKnownRelayedHapiCallWithGasLimit(Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, subject.offeredGasCost());
    }

    @Test
    void validateContractCallState() {
        final var subject = TestHelpers.wellKnownHapiCall();
        assertTrue(subject.isContractCall());
        assertFalse(subject.isException());
    }

    @Test
    void fromCodeDelegationResultUpdatesGasCostAndRefund() {
        final var subject = TestHelpers.wellKnownHapiCall();
        final var codeDelegationResult =
                mock(com.hedera.node.app.service.contract.impl.exec.delegation.CodeDelegationResult.class);
        final var availableGas = GAS_LIMIT - 10_000L;
        final var refund = 5_000L;

        given(codeDelegationResult.getAvailableGas()).willReturn(availableGas);
        given(codeDelegationResult.getGetRefund()).willReturn(refund);

        final var result = subject.fromCodeDelegationResult(codeDelegationResult);

        assertEquals(10_000L, result.codeDelegationGasCost());
        assertEquals(refund, result.codeDelegationGasRefund());
        assertEquals(subject.senderId(), result.senderId());
        assertEquals(subject.relayerId(), result.relayerId());
        assertEquals(subject.contractId(), result.contractId());
        assertEquals(subject.nonce(), result.nonce());
        assertEquals(subject.payload(), result.payload());
        assertEquals(subject.chainId(), result.chainId());
        assertEquals(subject.value(), result.value());
        assertEquals(subject.gasLimit(), result.gasLimit());
        assertEquals(subject.offeredGasPrice(), result.offeredGasPrice());
        assertEquals(subject.maxGasAllowance(), result.maxGasAllowance());
    }
}
