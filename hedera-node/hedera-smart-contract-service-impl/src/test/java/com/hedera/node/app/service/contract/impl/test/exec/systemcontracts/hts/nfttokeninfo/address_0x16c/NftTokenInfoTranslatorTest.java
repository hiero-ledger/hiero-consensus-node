// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.nfttokeninfo.address_0x16c;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_16C_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.nfttokeninfo.address_0x16c.NftTokenInfoTranslator.NON_FUNGIBLE_TOKEN_INFO_16C;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.nfttokeninfo.NftTokenInfoCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.nfttokeninfo.address_0x16c.NftTokenInfoTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import com.swirlds.config.api.Configuration;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class NftTokenInfoTranslatorTest extends CallAttemptTestBase {
    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private ContractMetrics contractMetrics;

    @Mock
    private Configuration configuration;

    private NftTokenInfoTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new NftTokenInfoTranslator(systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesTokenInfoTranslatorTestV2() {
        attempt = createHtsCallAttempt(HTS_16C_CONTRACT_ID, NON_FUNGIBLE_TOKEN_INFO_16C, subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void callFromTestV2() {
        final Tuple tuple = Tuple.of(FUNGIBLE_TOKEN_HEADLONG_ADDRESS, 0L);
        final Bytes inputBytes = Bytes.wrapByteBuffer(NON_FUNGIBLE_TOKEN_INFO_16C.encodeCall(tuple));
        given(attempt.input()).willReturn(inputBytes);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.configuration()).willReturn(configuration);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        final var call = subject.callFrom(attempt);
        assertThat(call).isInstanceOf(NftTokenInfoCall.class);
    }
}
