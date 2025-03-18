// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.fungibletokeninfo.address_0x16c;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_16C_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.fungibletokeninfo.address_0x16c.FungibleTokenInfoTranslator.FUNGIBLE_TOKEN_INFO_16C;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.fungibletokeninfo.FungibleTokenInfoCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.fungibletokeninfo.address_0x16c.FungibleTokenInfoTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import com.swirlds.config.api.Configuration;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class FungibleTokenInfoTranslatorTest extends CallAttemptTestBase {

    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private Configuration configuration;

    @Mock
    private ContractMetrics contractMetrics;

    private FungibleTokenInfoTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new FungibleTokenInfoTranslator(systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesFungibleTokenInfoTranslatorTest16c() {
        attempt = createHtsCallAttempt(HTS_16C_CONTRACT_ID, FUNGIBLE_TOKEN_INFO_16C, subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void callFromTest16c() {
        final Tuple tuple = Tuple.singleton(FUNGIBLE_TOKEN_HEADLONG_ADDRESS);
        final Bytes inputBytes = Bytes.wrapByteBuffer(FUNGIBLE_TOKEN_INFO_16C.encodeCall(tuple));
        given(attempt.input()).willReturn(inputBytes);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);
        given(attempt.configuration()).willReturn(configuration);

        final var call = subject.callFrom(attempt);
        assertThat(call).isInstanceOf(FungibleTokenInfoCall.class);
    }
}
