/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
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

package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALL_DATA;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OUTPUT_DATA;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.REQUIRED_GAS;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.pbjToTuweniBytes;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.HederaSystemContract;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.precompile.PrecompiledContract;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HederaSystemContractTest {
    @Mock
    private MessageFrame messageFrame;

    @Mock
    private ContractID contractID;

    @Test
    void defaultFullComputationDelegates() {
        final var input = pbjToTuweniBytes(CALL_DATA);
        final var output = pbjToTuweniBytes(OUTPUT_DATA);
        final var expectedResult = PrecompiledContract.PrecompileContractResult.success(output);
        final var subject = mock(HederaSystemContract.class);
        given(subject.computePrecompile(input, messageFrame)).willReturn(expectedResult);
        given(subject.gasRequirement(input)).willReturn(REQUIRED_GAS);
        doCallRealMethod().when(subject).computeFully(contractID, input, messageFrame);

        final var fullResult = subject.computeFully(contractID, input, messageFrame);
        Assertions.assertEquals(expectedResult, fullResult.result());
        Assertions.assertEquals(REQUIRED_GAS, fullResult.gasRequirement());
    }
}
