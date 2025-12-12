// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.delegation;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_LONG_ZERO_ADDRESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.node.app.service.contract.impl.exec.delegation.CodeDelegationResult;
import org.junit.jupiter.api.Test;

class CodeDelegationResultTest {

    private final CodeDelegationResult subject = new CodeDelegationResult();

    @Test
    void testConstructorAndAccessors() {
        assertTrue(subject.accessedDelegatorAddresses().isEmpty());

        subject.addAccessedDelegatorAddress(NON_SYSTEM_LONG_ZERO_ADDRESS);
        assertTrue(subject.accessedDelegatorAddresses().contains(NON_SYSTEM_LONG_ZERO_ADDRESS));

        subject.incrementAlreadyExistingDelegators();
        assertEquals(1L, subject.alreadyExistingDelegators());
    }
}
