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

package com.hedera.node.app.service.contract.impl.test.exec.processors;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HasSystemContract.HAS_EVM_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HssSystemContract.HSS_EVM_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_167_EVM_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_16C_EVM_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.PrngSystemContract.PRNG_PRECOMPILE_ADDRESS;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.app.service.contract.impl.exec.processors.ProcessorModule;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.ExchangeRateSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.HasSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.HssSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.PrngSystemContract;
import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProcessorModuleTest {
    @Mock
    private HtsSystemContract htsSystemContract;

    @Mock
    private ExchangeRateSystemContract exchangeRateSystemContract;

    @Mock
    private PrngSystemContract prngSystemContract;

    @Mock
    private HasSystemContract hasSystemContract;

    @Mock
    private HssSystemContract hssSystemContract;

    @Test
    void provideHederaSystemContracts() {
        final var hederaSystemContracts = ProcessorModule.provideHederaSystemContracts(
                htsSystemContract,
                exchangeRateSystemContract,
                prngSystemContract,
                hasSystemContract,
                hssSystemContract);
        assertThat(hederaSystemContracts)
                .isNotNull()
                .hasSize(6)
                .containsKey(Address.fromHexString(HTS_167_EVM_ADDRESS))
                .containsKey(Address.fromHexString(HTS_16C_EVM_ADDRESS))
                .containsKey(Address.fromHexString(ExchangeRateSystemContract.EXCHANGE_RATE_SYSTEM_CONTRACT_ADDRESS))
                .containsKey(Address.fromHexString(PRNG_PRECOMPILE_ADDRESS))
                .containsKey(Address.fromHexString(HAS_EVM_ADDRESS))
                .containsKey(Address.fromHexString(HSS_EVM_ADDRESS));
    }
}
