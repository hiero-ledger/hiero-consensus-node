/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
