// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.customfees;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.burn.BurnTranslator.BURN_TOKEN_V2;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.customfees.TokenCustomFeesTranslator.TOKEN_CUSTOM_FEES;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.customfees.TokenCustomFeesCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.customfees.TokenCustomFeesTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class TokenCustomFeesTranslatorTest extends CallAttemptTestBase {

    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private ContractMetrics contractMetrics;

    private TokenCustomFeesTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new TokenCustomFeesTranslator(systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesTokenCustomFeesTranslatorTest() {
        attempt = createHtsCallAttempt(TOKEN_CUSTOM_FEES, subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesFailsIfIncorrectSelectorTest() {
        attempt = createHtsCallAttempt(BURN_TOKEN_V2, subject);
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void callFromTest() {
        final Tuple tuple = Tuple.singleton(FUNGIBLE_TOKEN_HEADLONG_ADDRESS);
        final Bytes inputBytes = Bytes.wrapByteBuffer(TOKEN_CUSTOM_FEES.encodeCall(tuple));
        given(attempt.input()).willReturn(inputBytes);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        final var call = subject.callFrom(attempt);
        assertThat(call).isInstanceOf(TokenCustomFeesCall.class);
    }
}
