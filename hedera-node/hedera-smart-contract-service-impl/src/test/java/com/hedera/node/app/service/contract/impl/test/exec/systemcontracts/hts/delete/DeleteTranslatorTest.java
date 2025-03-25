// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.delete;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.burn.BurnTranslator.BURN_TOKEN_V2;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.delete.DeleteTranslator.DELETE_TOKEN;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_ACCOUNT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.DispatchForResponseCodeHtsCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.delete.DeleteTranslator;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class DeleteTranslatorTest extends CallAttemptTestBase {
    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private VerificationStrategy verificationStrategy;

    @Mock
    private ContractMetrics contractMetrics;

    private DeleteTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new DeleteTranslator(systemContractMethodRegistry, contractMetrics);
    }

    @Test
    void matchesDelete() {
        attempt = createHtsCallAttempt(DELETE_TOKEN, subject);
        assertThat(subject.identifyMethod(attempt)).isPresent();
    }

    @Test
    void matchesFailsIfIncorrectSelectorTest() {
        attempt = createHtsCallAttempt(BURN_TOKEN_V2, subject);
        assertThat(subject.identifyMethod(attempt)).isEmpty();
    }

    @Test
    void callFromDeleteTest() {
        Tuple tuple = Tuple.singleton(NON_FUNGIBLE_TOKEN_HEADLONG_ADDRESS);
        byte[] inputBytes = Bytes.wrapByteBuffer(DELETE_TOKEN.encodeCall(tuple)).toArray();
        given(attempt.inputBytes()).willReturn(inputBytes);
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(addressIdConverter.convertSender(any())).willReturn(NON_SYSTEM_ACCOUNT_ID);
        given(attempt.defaultVerificationStrategy()).willReturn(verificationStrategy);
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        final var call = subject.callFrom(attempt);
        assertThat(call).isInstanceOf(DispatchForResponseCodeHtsCall.class);
    }
}
