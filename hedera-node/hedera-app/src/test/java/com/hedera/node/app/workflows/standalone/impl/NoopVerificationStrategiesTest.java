// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.standalone.impl;

import static com.hedera.node.app.workflows.standalone.impl.NoopVerificationStrategies.NOOP_VERIFICATION_STRATEGIES;
import static org.junit.jupiter.api.Assertions.*;

import com.hedera.hapi.node.base.Key;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NoopVerificationStrategiesTest {
    @Mock
    private HederaNativeOperations nativeOperations;

    @Test
    void allKeysAreValid() {
        final var subject = NOOP_VERIFICATION_STRATEGIES.activatingOnlyContractKeysFor(
                Address.ALTBN128_ADD, true, nativeOperations);
        assertSame(VerificationStrategy.Decision.VALID, subject.decideForPrimitive(Key.DEFAULT));
    }
}
