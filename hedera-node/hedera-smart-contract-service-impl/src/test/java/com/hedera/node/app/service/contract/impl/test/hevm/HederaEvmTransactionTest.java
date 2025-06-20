// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.hevm;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.*;
import static org.junit.jupiter.api.Assertions.*;

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
}
