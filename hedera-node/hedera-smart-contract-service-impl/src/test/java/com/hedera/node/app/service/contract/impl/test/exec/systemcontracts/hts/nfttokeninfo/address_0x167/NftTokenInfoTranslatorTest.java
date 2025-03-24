// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.nfttokeninfo.address_0x167;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.burn.BurnTranslator.BURN_TOKEN_V2;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.nfttokeninfo.address_0x167.NftTokenInfoTranslator.NON_FUNGIBLE_TOKEN_INFO;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.nfttokeninfo.NftTokenInfoCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.nfttokeninfo.address_0x167.NftTokenInfoTranslator;
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
    private Configuration configuration;

    @Mock
    private ContractMetrics contractMetrics;

    private NftTokenInfoTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new NftTokenInfoTranslator(systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesTokenInfoTranslatorTest() {
        attempt = createHtsCallAttempt(NON_FUNGIBLE_TOKEN_INFO, subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesFailsIfIncorrectSelectorTest() {
        attempt = createHtsCallAttempt(BURN_TOKEN_V2, subject);
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void callFromTest() {
        final Tuple tuple = Tuple.of(FUNGIBLE_TOKEN_HEADLONG_ADDRESS, 0L);
        final Bytes inputBytes = Bytes.wrapByteBuffer(NON_FUNGIBLE_TOKEN_INFO.encodeCall(tuple));
        given(attempt.input()).willReturn(inputBytes);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.configuration()).willReturn(configuration);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        final var call = subject.callFrom(attempt);
        assertThat(call).isInstanceOf(NftTokenInfoCall.class);
    }
}
