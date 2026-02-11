// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.delegation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.node.app.service.contract.impl.exec.delegation.CodeDelegationResult;
import org.junit.jupiter.api.Test;

class CodeDelegationResultTest {
    @Test
    void testAddingCharges() {
        final var subject = new CodeDelegationResult(10000);
        assertEquals(0, subject.numAuthorizationsEligibleForRefund());
        assertEquals(0, subject.totalLazyCreationGasCharged());

        subject.addHollowAccountCreationGasCharge(200);
        subject.incAuthorizationsEligibleForRefund();
        assertEquals(1, subject.numAuthorizationsEligibleForRefund());
        assertEquals(200L, subject.totalLazyCreationGasCharged());
        assertEquals(9800, subject.remainingLazyCreationGasAvailable());
    }
}
