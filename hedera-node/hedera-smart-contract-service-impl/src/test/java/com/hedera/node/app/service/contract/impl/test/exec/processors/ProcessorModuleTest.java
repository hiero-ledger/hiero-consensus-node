// SPDX-License-Identifier: Apache-2.0
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
