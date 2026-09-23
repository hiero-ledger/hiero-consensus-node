// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.processors;

import static com.hedera.node.app.service.clpr.ClprServiceConstants.CLPR_EVM_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.BesuQBFTVerifierSystemContract.BESU_QBFT_VERIFIER_EVM_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.EthereumVerifierSystemContract.ETHEREUM_VERIFIER_EVM_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HasSystemContract.HAS_EVM_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HssSystemContract.HSS_EVM_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_167_EVM_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_16C_EVM_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.PrngSystemContract.PRNG_PRECOMPILE_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.SeiVerifierSystemContract.SEI_VERIFIER_EVM_ADDRESS;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.app.service.contract.impl.exec.processors.ProcessorModule;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.BesuQBFTVerifierSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.ClprSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.EthereumVerifierSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.ExchangeRateSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.HasSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.HssSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.PrngSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.SeiVerifierSystemContract;
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

    @Mock
    private ClprSystemContract clprSystemContract;

    @Mock
    private BesuQBFTVerifierSystemContract besuQBFTVerifierSystemContract;

    @Mock
    private SeiVerifierSystemContract seiVerifierSystemContract;

    @Mock
    private EthereumVerifierSystemContract ethereumVerifierSystemContract;

    @Test
    void provideHederaSystemContracts() {
        final var hederaSystemContracts = ProcessorModule.provideHederaSystemContracts(
                htsSystemContract,
                exchangeRateSystemContract,
                prngSystemContract,
                hasSystemContract,
                hssSystemContract,
                clprSystemContract,
                besuQBFTVerifierSystemContract,
                seiVerifierSystemContract,
                ethereumVerifierSystemContract);
        assertThat(hederaSystemContracts)
                .isNotNull()
                .hasSize(10)
                .containsKey(Address.fromHexString(HTS_167_EVM_ADDRESS))
                .containsKey(Address.fromHexString(HTS_16C_EVM_ADDRESS))
                .containsKey(Address.fromHexString(ExchangeRateSystemContract.EXCHANGE_RATE_SYSTEM_CONTRACT_ADDRESS))
                .containsKey(Address.fromHexString(PRNG_PRECOMPILE_ADDRESS))
                .containsKey(Address.fromHexString(HAS_EVM_ADDRESS))
                .containsKey(Address.fromHexString(HSS_EVM_ADDRESS))
                .containsKey(Address.fromHexString(CLPR_EVM_ADDRESS))
                .containsKey(Address.fromHexString(BESU_QBFT_VERIFIER_EVM_ADDRESS))
                .containsKey(Address.fromHexString(SEI_VERIFIER_EVM_ADDRESS))
                .containsKey(Address.fromHexString(ETHEREUM_VERIFIER_EVM_ADDRESS));
    }
}
