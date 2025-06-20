// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.iskyc;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.burn.BurnTranslator.BURN_TOKEN_V2;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.iskyc.IsKycTranslator.IS_KYC;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.fromHeadlongAddress;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.iskyc.IsKycCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.iskyc.IsKycTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class IsKycTranslatorTest extends CallAttemptTestBase {
    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private Token token;

    @Mock
    private ContractMetrics contractMetrics;

    private IsKycTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new IsKycTranslator(systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesIsKycTest() {
        attempt = createHtsCallAttempt(IS_KYC, subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesFailsIfIncorrectSelectorTest() {
        attempt = createHtsCallAttempt(BURN_TOKEN_V2, subject);
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void callFromTest() {
        final Tuple tuple = Tuple.of(FUNGIBLE_TOKEN_HEADLONG_ADDRESS, OWNER_HEADLONG_ADDRESS);
        final Bytes inputBytes = Bytes.wrapByteBuffer(IS_KYC.encodeCall(tuple));
        given(attempt.input()).willReturn(inputBytes);
        given(attempt.linkedToken(fromHeadlongAddress(FUNGIBLE_TOKEN_HEADLONG_ADDRESS)))
                .willReturn(token);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        final var call = subject.callFrom(attempt);
        assertThat(call).isInstanceOf(IsKycCall.class);
    }
}
