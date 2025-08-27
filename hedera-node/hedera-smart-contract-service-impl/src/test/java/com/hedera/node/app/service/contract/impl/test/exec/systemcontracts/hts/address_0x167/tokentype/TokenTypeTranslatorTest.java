// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.address_0x167.tokentype;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_16C_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.burn.BurnTranslator.BURN_TOKEN_V2;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.tokentype.address_0x167.TokenTypeTranslator.TOKEN_TYPE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.tokentype.TokenTypeCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.tokentype.address_0x167.TokenTypeTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class TokenTypeTranslatorTest extends CallAttemptTestBase {
    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private ContractMetrics contractMetrics;

    private TokenTypeTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new TokenTypeTranslator(systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesTokenTypeTest() {
        attempt = createHtsCallAttempt(TOKEN_TYPE, subject);
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
        final Bytes inputBytes = Bytes.wrapByteBuffer(TOKEN_TYPE.encodeCall(tuple));
        given(attempt.input()).willReturn(inputBytes);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        final var call = subject.callFrom(attempt);
        assertThat(call).isInstanceOf(TokenTypeCall.class);
    }

    @Test
    void validateMatchingContractIDTest() {
        attempt = createHtsCallAttempt(TOKEN_TYPE, subject);
        assertThat(attempt.isMethod(TOKEN_TYPE)).isPresent();
    }

    @Test
    void validateNonMatchingContractIDTest() {
        attempt = createHtsCallAttempt(HTS_16C_CONTRACT_ID, TOKEN_TYPE, subject);
        assertThat(attempt.isMethod(TOKEN_TYPE)).isNotPresent();
    }
}
